package dev.ai4j.openai4j.chat;

import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.RateLimitAwareTest;
import dev.ai4j.openai4j.ResponseHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.ai4j.openai4j.chat.ChatCompletionModel.GPT_4_VISION_PREVIEW;
import static dev.ai4j.openai4j.chat.ChatCompletionTest.*;
import static dev.ai4j.openai4j.chat.FunctionCallUtil.argument;
import static dev.ai4j.openai4j.chat.FunctionCallUtil.argumentsAsMap;
import static dev.ai4j.openai4j.chat.ResponseFormatType.JSON_OBJECT;
import static dev.ai4j.openai4j.chat.ResponseFormatType.TEXT;
import static dev.ai4j.openai4j.chat.ToolType.FUNCTION;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;
import static org.junit.jupiter.params.provider.EnumSource.Mode.INCLUDE;

class ChatCompletionStreamingTest extends RateLimitAwareTest {

    private final OpenAiClient client = OpenAiClient.builder()
            .openAiApiKey(System.getenv("OPENAI_API_KEY"))
            .logRequests()
            .logResponses()
            .logStreamingResponses()
            .build();

    @Test
    void testSimpleApi() throws Exception {

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> future = new CompletableFuture<>();

        client.chatCompletion(USER_MESSAGE)
                .onPartialResponse(responseBuilder::append)
                .onComplete(() -> future.complete(responseBuilder.toString()))
                .onError(future::completeExceptionally)
                .execute();

        String response = future.get(30, SECONDS);

        // then
        assertThat(response).containsIgnoringCase("hello world");
    }

    @ParameterizedTest
    @EnumSource(value = ChatCompletionModel.class, mode = EXCLUDE, names = {
            "GPT_4_32K", "GPT_4_32K_0314", "GPT_4_32K_0613", // I don't have access to these models
            "GPT_4_VISION_PREVIEW" // Does not support many things now, including logit_bias and response_format
    })
    void testCustomizableApi(ChatCompletionModel model) throws Exception {

        // given
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .addSystemMessage(SYSTEM_MESSAGE)
                .addUserMessage(USER_MESSAGE)
                .temperature(1.0)
                .topP(0.1)
                .n(1)
                .stream(false) // intentionally setting to false in order to test that it is ignored
                .stop("one", "two")
                .maxTokens(3)
                .presencePenalty(0.0)
                .frequencyPenalty(0.0)
                .logitBias(singletonMap("50256", -100))
                .user("Klaus")
                .responseFormat(TEXT)
                .seed(42)
                .build();

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> future = new CompletableFuture<>();

        client.chatCompletion(request)
                .onPartialResponse(partialResponse -> {
                    String content = partialResponse.choices().get(0).delta().content();
                    if (content != null) {
                        responseBuilder.append(content);
                    }
                })
                .onComplete(() -> future.complete(responseBuilder.toString()))
                .onError(future::completeExceptionally)
                .execute();

        String response = future.get(30, SECONDS);

        // then
        assertThat(response).containsIgnoringCase("hello world");
    }

    @ParameterizedTest
    @EnumSource(value = ChatCompletionModel.class, mode = EXCLUDE, names = {
            "GPT_4_32K", "GPT_4_32K_0314", "GPT_4_32K_0613", // I don't have access to these models
            "GPT_4_0314", // Does not support tools/functions
            "GPT_4_VISION_PREVIEW" // Does not support many things now, including tools
    })
    void testTools(ChatCompletionModel model) throws Exception {

        // given
        UserMessage userMessage = UserMessage.from("What is the weather like in Boston?");

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage)
                .tools(WEATHER_TOOL)
                .build();

        // when
        StringBuilder toolIdBuilder = new StringBuilder();
        StringBuilder functionNameBuilder = new StringBuilder();
        StringBuilder functionArgumentsBuilder = new StringBuilder();
        CompletableFuture<AssistantMessage> future = new CompletableFuture<>();

        client.chatCompletion(request)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    assertThat(delta.content()).isNull();
                    assertThat(delta.functionCall()).isNull();

                    if (delta.toolCalls() != null) {
                        assertThat(delta.toolCalls()).hasSize(1);

                        ToolCall toolCall = delta.toolCalls().get(0);
                        assertThat(toolCall.type()).isIn(null, FUNCTION);
                        assertThat(toolCall.function()).isNotNull();

                        if (toolCall.id() != null) {
                            toolIdBuilder.append(toolCall.id());
                        }

                        FunctionCall functionCall = toolCall.function();
                        if (functionCall.name() != null) {
                            functionNameBuilder.append(functionCall.name());
                        }
                        if (functionCall.arguments() != null) {
                            functionArgumentsBuilder.append(functionCall.arguments());
                        }
                    }
                })
                .onComplete(() -> {
                    AssistantMessage assistantMessage = AssistantMessage.builder()
                            .toolCalls(ToolCall.builder()
                                    .id(toolIdBuilder.toString())
                                    .type(FUNCTION)
                                    .function(FunctionCall.builder()
                                            .name(functionNameBuilder.toString())
                                            .arguments(functionArgumentsBuilder.toString())
                                            .build())
                                    .build())
                            .build();
                    future.complete(assistantMessage);
                })
                .onError(future::completeExceptionally)
                .execute();

        AssistantMessage assistantMessage = future.get(30, SECONDS);

        // then
        assertThat(assistantMessage.content()).isNull();
        assertThat(assistantMessage.functionCall()).isNull();
        assertThat(assistantMessage.toolCalls()).isNotNull().hasSize(1);

        ToolCall toolCall = assistantMessage.toolCalls().get(0);
        assertThat(toolCall.id()).isNotBlank();
        assertThat(toolCall.type()).isEqualTo(FUNCTION);
        assertThat(toolCall.function()).isNotNull();

        FunctionCall functionCall = toolCall.function();
        assertThat(functionCall.name()).isEqualTo(WEATHER_TOOL_NAME);
        assertThat(functionCall.arguments()).isNotBlank();

        Map<String, Object> arguments = argumentsAsMap(functionCall.arguments());
        assertThat(arguments).hasSizeBetween(1, 2);
        assertThat(arguments.get("location").toString()).contains("Boston");

        // given
        String location = argument("location", functionCall);
        String unit = argument("unit", functionCall);
        String currentWeather = currentWeather(location, unit);
        ToolMessage toolMessage = ToolMessage.from(toolCall.id(), currentWeather);

        ChatCompletionRequest secondRequest = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage, assistantMessage, toolMessage)
                .build();

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> secondFuture = new CompletableFuture<>();

        client.chatCompletion(secondRequest)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    if (delta.content() != null) {
                        responseBuilder.append(delta.content());
                    }
                })
                .onComplete(() -> secondFuture.complete(responseBuilder.toString()))
                .onError(secondFuture::completeExceptionally)
                .execute();

        String response = secondFuture.get(30, SECONDS);

        // then
        assertThat(response).contains("11");
    }

    @ParameterizedTest
    @EnumSource(value = ChatCompletionModel.class, mode = EXCLUDE, names = {
            "GPT_4_32K", "GPT_4_32K_0314", "GPT_4_32K_0613", // I don't have access to these models
            "GPT_4_0314", // Does not support tools/functions
            "GPT_4_VISION_PREVIEW" // Does not support many things now, including tools
    })
    void testFunctions(ChatCompletionModel model) throws Exception {

        // given
        UserMessage userMessage = UserMessage.from("What is the weather like in Boston?");

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage)
                .functions(WEATHER_FUNCTION)
                .build();

        // when
        StringBuilder functionNameBuilder = new StringBuilder();
        StringBuilder functionArgumentsBuilder = new StringBuilder();
        CompletableFuture<AssistantMessage> future = new CompletableFuture<>();

        client.chatCompletion(request)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    assertThat(delta.content()).isNull();
                    assertThat(delta.toolCalls()).isNull();

                    if (delta.functionCall() != null) {
                        FunctionCall functionCall = delta.functionCall();
                        if (functionCall.name() != null) {
                            functionNameBuilder.append(functionCall.name());
                        }
                        if (functionCall.arguments() != null) {
                            functionArgumentsBuilder.append(functionCall.arguments());
                        }
                    }
                })
                .onComplete(() -> {
                    AssistantMessage assistantMessage = AssistantMessage.builder()
                            .functionCall(FunctionCall.builder()
                                    .name(functionNameBuilder.toString())
                                    .arguments(functionArgumentsBuilder.toString())
                                    .build())
                            .build();
                    future.complete(assistantMessage);
                })
                .onError(future::completeExceptionally)
                .execute();

        AssistantMessage assistantMessage = future.get(30, SECONDS);

        // then
        assertThat(assistantMessage.content()).isNull();
        assertThat(assistantMessage.toolCalls()).isNull();

        FunctionCall functionCall = assistantMessage.functionCall();
        assertThat(functionCall.name()).isEqualTo(WEATHER_TOOL_NAME);
        assertThat(functionCall.arguments()).isNotBlank();

        Map<String, Object> arguments = argumentsAsMap(functionCall.arguments());
        assertThat(arguments).hasSizeBetween(1, 2);
        assertThat(arguments.get("location").toString()).contains("Boston");

        // given
        String location = argument("location", functionCall);
        String unit = argument("unit", functionCall);
        String currentWeather = currentWeather(location, unit);
        FunctionMessage functionMessage = FunctionMessage.from(functionCall.name(), currentWeather);

        ChatCompletionRequest secondRequest = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage, assistantMessage, functionMessage)
                .build();

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> secondFuture = new CompletableFuture<>();

        client.chatCompletion(secondRequest)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    if (delta.content() != null) {
                        responseBuilder.append(delta.content());
                    }
                })
                .onComplete(() -> secondFuture.complete(responseBuilder.toString()))
                .onError(secondFuture::completeExceptionally)
                .execute();

        String response = secondFuture.get(30, SECONDS);

        // then
        assertThat(response).contains("11");
    }

    @ParameterizedTest
    @EnumSource(value = ChatCompletionModel.class, mode = EXCLUDE, names = {
            "GPT_4_32K", "GPT_4_32K_0314", "GPT_4_32K_0613", // I don't have access to these models
            "GPT_4_0314", // Does not support tools/functions
            "GPT_4_VISION_PREVIEW" // does not support many things now, including tools
    })
    void testToolChoice(ChatCompletionModel model) throws Exception {

        // given
        UserMessage userMessage = UserMessage.from("What is the weather like in Boston?");

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage)
                .tools(WEATHER_TOOL)
                .toolChoice(WEATHER_TOOL_NAME)
                .build();

        // when
        StringBuilder toolIdBuilder = new StringBuilder();
        StringBuilder functionNameBuilder = new StringBuilder();
        StringBuilder functionArgumentsBuilder = new StringBuilder();
        CompletableFuture<AssistantMessage> future = new CompletableFuture<>();

        client.chatCompletion(request)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    assertThat(delta.content()).isNull();
                    assertThat(delta.functionCall()).isNull();

                    if (delta.toolCalls() != null) {
                        assertThat(delta.toolCalls()).hasSize(1);

                        ToolCall toolCall = delta.toolCalls().get(0);
                        assertThat(toolCall.type()).isIn(null, FUNCTION);
                        assertThat(toolCall.function()).isNotNull();

                        if (toolCall.id() != null) {
                            toolIdBuilder.append(toolCall.id());
                        }

                        FunctionCall functionCall = toolCall.function();
                        if (functionCall.name() != null) {
                            functionNameBuilder.append(functionCall.name());
                        }
                        if (functionCall.arguments() != null) {
                            functionArgumentsBuilder.append(functionCall.arguments());
                        }
                    }
                })
                .onComplete(() -> {
                    AssistantMessage assistantMessage = AssistantMessage.builder()
                            .toolCalls(ToolCall.builder()
                                    .id(toolIdBuilder.toString())
                                    .type(FUNCTION)
                                    .function(FunctionCall.builder()
                                            .name(functionNameBuilder.toString())
                                            .arguments(functionArgumentsBuilder.toString())
                                            .build())
                                    .build())
                            .build();
                    future.complete(assistantMessage);
                })
                .onError(future::completeExceptionally)
                .execute();

        AssistantMessage assistantMessage = future.get(30, SECONDS);

        // then
        assertThat(assistantMessage.content()).isNull();
        assertThat(assistantMessage.toolCalls()).isNotNull().hasSize(1);

        ToolCall toolCall = assistantMessage.toolCalls().get(0);
        assertThat(toolCall.id()).isNotBlank();
        assertThat(toolCall.type()).isEqualTo(FUNCTION);
        assertThat(toolCall.function()).isNotNull();

        FunctionCall functionCall = toolCall.function();
        assertThat(functionCall.name()).isEqualTo(WEATHER_TOOL_NAME);
        assertThat(functionCall.arguments()).isNotBlank();

        Map<String, Object> arguments = argumentsAsMap(functionCall.arguments());
        assertThat(arguments).hasSizeBetween(1, 2);
        assertThat(arguments.get("location").toString()).contains("Boston");

        // given
        String location = argument("location", functionCall);
        String unit = argument("unit", functionCall);
        String currentWeather = currentWeather(location, unit);
        ToolMessage toolMessage = ToolMessage.from(toolCall.id(), currentWeather);

        ChatCompletionRequest secondRequest = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage, assistantMessage, toolMessage)
                .build();

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> secondFuture = new CompletableFuture<>();

        client.chatCompletion(secondRequest)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    if (delta.content() != null) {
                        responseBuilder.append(delta.content());
                    }
                })
                .onComplete(() -> secondFuture.complete(responseBuilder.toString()))
                .onError(secondFuture::completeExceptionally)
                .execute();

        String response = secondFuture.get(30, SECONDS);

        // then
        assertThat(response).contains("11");
    }

    @ParameterizedTest
    @EnumSource(value = ChatCompletionModel.class, mode = EXCLUDE, names = {
            "GPT_4_32K", "GPT_4_32K_0314", "GPT_4_32K_0613", // I don't have access to these models
            "GPT_4_0314", // Does not support tools/functions
            "GPT_4_VISION_PREVIEW" // does not support many things now, including tools
    })
    void testFunctionChoice(ChatCompletionModel model) throws Exception {

        // given
        UserMessage userMessage = UserMessage.from("What is the weather like in Boston?");

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage)
                .functions(WEATHER_FUNCTION)
                .functionCall(WEATHER_TOOL_NAME)
                .build();

        // when
        StringBuilder functionNameBuilder = new StringBuilder();
        StringBuilder functionArgumentsBuilder = new StringBuilder();
        CompletableFuture<AssistantMessage> future = new CompletableFuture<>();

        client.chatCompletion(request)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    assertThat(delta.content()).isNull();
                    assertThat(delta.toolCalls()).isNull();

                    if (delta.functionCall() != null) {
                        FunctionCall functionCall = delta.functionCall();
                        if (functionCall.name() != null) {
                            functionNameBuilder.append(functionCall.name());
                        }
                        if (functionCall.arguments() != null) {
                            functionArgumentsBuilder.append(functionCall.arguments());
                        }
                    }
                })
                .onComplete(() -> {
                    AssistantMessage assistantMessage = AssistantMessage.builder()
                            .functionCall(FunctionCall.builder()
                                    .name(functionNameBuilder.toString())
                                    .arguments(functionArgumentsBuilder.toString())
                                    .build())
                            .build();
                    future.complete(assistantMessage);
                })
                .onError(future::completeExceptionally)
                .execute();

        AssistantMessage assistantMessage = future.get(30, SECONDS);

        // then
        assertThat(assistantMessage.content()).isNull();
        assertThat(assistantMessage.toolCalls()).isNull();

        FunctionCall functionCall = assistantMessage.functionCall();
        assertThat(functionCall.name()).isEqualTo(WEATHER_TOOL_NAME);
        assertThat(functionCall.arguments()).isNotBlank();

        Map<String, Object> arguments = argumentsAsMap(functionCall.arguments());
        assertThat(arguments).hasSizeBetween(1, 2);
        assertThat(arguments.get("location").toString()).contains("Boston");

        // given
        String location = argument("location", functionCall);
        String unit = argument("unit", functionCall);
        String currentWeather = currentWeather(location, unit);
        FunctionMessage functionMessage = FunctionMessage.from(functionCall.name(), currentWeather);

        ChatCompletionRequest secondRequest = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage, assistantMessage, functionMessage)
                .build();

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> secondFuture = new CompletableFuture<>();

        client.chatCompletion(secondRequest)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    if (delta.content() != null) {
                        responseBuilder.append(delta.content());
                    }
                })
                .onComplete(() -> secondFuture.complete(responseBuilder.toString()))
                .onError(secondFuture::completeExceptionally)
                .execute();

        String response = secondFuture.get(30, SECONDS);

        // then
        assertThat(response).contains("11");
    }

    @ParameterizedTest
    @EnumSource(value = ChatCompletionModel.class, mode = INCLUDE, names = {"GPT_3_5_TURBO_1106", "GPT_4_1106_PREVIEW"})
    void testParallelTools(ChatCompletionModel model) throws Exception {

        // given
        UserMessage userMessage = UserMessage.from("What is the weather like in Boston and Madrid?");

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage)
                .tools(WEATHER_TOOL)
                .build();

        // when
        StringBuilder firstToolIdBuilder = new StringBuilder();
        StringBuilder firstFunctionNameBuilder = new StringBuilder();
        StringBuilder firstFunctionArgumentsBuilder = new StringBuilder();

        StringBuilder secondToolIdBuilder = new StringBuilder();
        StringBuilder secondFunctionNameBuilder = new StringBuilder();
        StringBuilder secondFunctionArgumentsBuilder = new StringBuilder();

        CompletableFuture<AssistantMessage> future = new CompletableFuture<>();

        client.chatCompletion(request)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    assertThat(delta.content()).isNull();
                    assertThat(delta.functionCall()).isNull();

                    if (delta.toolCalls() != null) {
                        assertThat(delta.toolCalls()).hasSize(1);

                        ToolCall toolCall = delta.toolCalls().get(0);
                        assertThat(toolCall.index()).isIn(0, 1);
                        assertThat(toolCall.type()).isIn(null, FUNCTION);
                        assertThat(toolCall.function()).isNotNull();

                        if (toolCall.id() != null) {
                            if (toolCall.index() == 0) {
                                firstToolIdBuilder.append(toolCall.id());
                            } else {
                                secondToolIdBuilder.append(toolCall.id());
                            }
                        }

                        FunctionCall functionCall = toolCall.function();
                        if (functionCall.name() != null) {
                            if (toolCall.index() == 0) {
                                firstFunctionNameBuilder.append(functionCall.name());
                            } else {
                                secondFunctionNameBuilder.append(functionCall.name());
                            }
                        }
                        if (functionCall.arguments() != null) {
                            if (toolCall.index() == 0) {
                                firstFunctionArgumentsBuilder.append(functionCall.arguments());
                            } else {
                                secondFunctionArgumentsBuilder.append(functionCall.arguments());
                            }
                        }
                    }
                })
                .onComplete(() -> {
                    AssistantMessage assistantMessage = AssistantMessage.builder()
                            .toolCalls(
                                    ToolCall.builder()
                                            .id(firstToolIdBuilder.toString())
                                            .type(FUNCTION)
                                            .function(FunctionCall.builder()
                                                    .name(firstFunctionNameBuilder.toString())
                                                    .arguments(firstFunctionArgumentsBuilder.toString())
                                                    .build())
                                            .build(),
                                    ToolCall.builder()
                                            .id(secondToolIdBuilder.toString())
                                            .type(FUNCTION)
                                            .function(FunctionCall.builder()
                                                    .name(secondFunctionNameBuilder.toString())
                                                    .arguments(secondFunctionArgumentsBuilder.toString())
                                                    .build())
                                            .build()
                            ).build();
                    future.complete(assistantMessage);
                })
                .onError(future::completeExceptionally)
                .execute();

        AssistantMessage assistantMessage = future.get(30, SECONDS);

        // then
        assertThat(assistantMessage.content()).isNull();
        assertThat(assistantMessage.toolCalls()).isNotNull().hasSize(2);

        ToolCall toolCall1 = assistantMessage.toolCalls().get(0);
        assertThat(toolCall1.id()).isNotBlank();
        assertThat(toolCall1.type()).isEqualTo(FUNCTION);
        assertThat(toolCall1.function()).isNotNull();

        FunctionCall functionCall1 = toolCall1.function();
        assertThat(functionCall1.name()).isEqualTo(WEATHER_TOOL_NAME);
        assertThat(functionCall1.arguments()).isNotBlank();

        Map<String, Object> arguments1 = argumentsAsMap(functionCall1.arguments());
        assertThat(arguments1).hasSizeBetween(1, 2);
        assertThat(arguments1.get("location").toString()).contains("Boston");

        ToolCall toolCall2 = assistantMessage.toolCalls().get(1);
        assertThat(toolCall2.id()).isNotBlank();
        assertThat(toolCall2.type()).isEqualTo(FUNCTION);
        assertThat(toolCall2.function()).isNotNull();

        FunctionCall functionCall2 = toolCall2.function();
        assertThat(functionCall2.name()).isEqualTo(WEATHER_TOOL_NAME);
        assertThat(functionCall2.arguments()).isNotBlank();

        Map<String, Object> arguments2 = argumentsAsMap(functionCall2.arguments());
        assertThat(arguments2).hasSizeBetween(1, 2);
        assertThat(arguments2.get("location").toString()).contains("Madrid");

        // given
        String location1 = argument("location", functionCall1);
        String unit1 = argument("unit", functionCall1);
        String currentWeather1 = currentWeather(location1, unit1);
        ToolMessage toolMessage1 = ToolMessage.from(toolCall1.id(), currentWeather1);

        String location2 = argument("location", functionCall2);
        String unit2 = argument("unit", functionCall2);
        String currentWeather2 = currentWeather(location2, unit2);
        ToolMessage toolMessage2 = ToolMessage.from(toolCall2.id(), currentWeather2);

        ChatCompletionRequest secondRequest = ChatCompletionRequest.builder()
                .model(model)
                .messages(userMessage, assistantMessage, toolMessage1, toolMessage2)
                .build();

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> secondFuture = new CompletableFuture<>();

        client.chatCompletion(secondRequest)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    if (delta.content() != null) {
                        responseBuilder.append(delta.content());
                    }
                })
                .onComplete(() -> secondFuture.complete(responseBuilder.toString()))
                .onError(secondFuture::completeExceptionally)
                .execute();

        String response = secondFuture.get(30, SECONDS);

        // then
        assertThat(response).contains("11", "22");
    }

    @ParameterizedTest
    @EnumSource(value = ChatCompletionModel.class, mode = INCLUDE, names = {"GPT_3_5_TURBO_1106", "GPT_4_1106_PREVIEW"})
    void testJsonResponseFormat(ChatCompletionModel model) throws Exception {

        // given
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .addUserMessage("Extract information from the following text:\n" +
                        "Who is Klaus Heisler?\n" +
                        "Respond in JSON format with two fields: 'name' and 'surname'")
                .responseFormat(JSON_OBJECT)
                .build();

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> future = new CompletableFuture<>();

        client.chatCompletion(request)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    if (delta.content() != null) {
                        responseBuilder.append(delta.content());
                    }
                })
                .onComplete(() -> future.complete(responseBuilder.toString()))
                .onError(future::completeExceptionally)
                .execute();

        String response = future.get(30, SECONDS);

        // then
        assertThat(response).isEqualToIgnoringWhitespace("{\"name\":\"Klaus\",\"surname\":\"Heisler\"}");
    }

    @Test
    void testGpt4Vision() throws Exception {

        // given
        String imageUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg";

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(GPT_4_VISION_PREVIEW)
                .messages(UserMessage.from("What is in this image?", imageUrl))
                .maxTokens(100)
                .build();

        // when
        StringBuilder responseBuilder = new StringBuilder();
        CompletableFuture<String> future = new CompletableFuture<>();

        client.chatCompletion(request)
                .onPartialResponse(partialResponse -> {
                    Delta delta = partialResponse.choices().get(0).delta();
                    if (delta.content() != null) {
                        responseBuilder.append(delta.content());
                    }
                })
                .onComplete(() -> future.complete(responseBuilder.toString()))
                .onError(future::completeExceptionally)
                .execute();

        String response = future.get(30, SECONDS);

        // then
        assertThat(response).containsIgnoringCase("green");
    }

    @Test
    void testCancelStreamingAfterStreamingStarted() throws Exception {

        AtomicBoolean streamingStarted = new AtomicBoolean(false);
        AtomicBoolean streamingCancelled = new AtomicBoolean(false);
        AtomicBoolean cancellationSucceeded = new AtomicBoolean(true);

        ResponseHandle responseHandle = client.chatCompletion("Write a poem about AI in 10 words")
                .onPartialResponse(partialResponse -> {
                    streamingStarted.set(true);
                    if (streamingCancelled.get()) {
                        cancellationSucceeded.set(false);
                    }
                })
                .onComplete(() -> cancellationSucceeded.set(false))
                .onError(e -> cancellationSucceeded.set(false))
                .execute();

        while (!streamingStarted.get()) {
            Thread.sleep(200);
        }

        newSingleThreadExecutor().execute(() -> {
            responseHandle.cancel();
            streamingCancelled.set(true);
        });

        while (!streamingCancelled.get()) {
            Thread.sleep(200);
        }
        Thread.sleep(5000);

        assertThat(cancellationSucceeded).isTrue();
    }

    @Test
    void testCancelStreamingBeforeStreamingStarted() throws Exception {

        AtomicBoolean cancellationSucceeded = new AtomicBoolean(true);

        ResponseHandle responseHandle = client.chatCompletion("Write a poem about AI in 10 words")
                .onPartialResponse(partialResponse -> cancellationSucceeded.set(false))
                .onComplete(() -> cancellationSucceeded.set(false))
                .onError(e -> cancellationSucceeded.set(false))
                .execute();

        AtomicBoolean streamingCancelled = new AtomicBoolean(false);

        newSingleThreadExecutor().execute(() -> {
            responseHandle.cancel();
            streamingCancelled.set(true);
        });

        while (!streamingCancelled.get()) {
            Thread.sleep(200);
        }
        Thread.sleep(5000);

        assertThat(cancellationSucceeded).isTrue();
    }
}

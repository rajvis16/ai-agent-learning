    package org.example;

    import java.util.ArrayList;
    import java.util.List;
    import java.util.Map;

    import java.net.URI;
    import java.net.http.HttpClient;
    import java.net.http.HttpRequest;
    import java.net.http.HttpResponse;
    import java.util.Scanner;

    import tools.jackson.databind.ObjectMapper;
    import tools.jackson.databind.JsonNode;

    public class OllamaToolCallingClient {

        private static String getOrderStatus(String orderId) {
            return switch (orderId) {
                case "456" -> "SHIPPED";
                case "789" -> "PROCESSING";
                default -> "NOT_FOUND";
            };
        }

        private static Map<String, Object> orderStatusTool() {
            return Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "getOrderStatus",
                            "description", "Look up the current status of an order.",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "orderId", Map.of(
                                                    "type", "string",
                                                    "description", "The order ID to look up."
                                            )
                                    ),
                                    "required", List.of("orderId")
                            )
                    )
            );
        }

        public static void main(String[] args) throws Exception {

            Scanner scanner = new Scanner(System.in);

            ObjectMapper objectMapper = new ObjectMapper();

            // Keep the complete conversation for this session.
            List<Map<?, ?>> messages = new ArrayList<>();
            messages.add(Map.of(
                    "role", "system",
                    "content", """
                Use the order lookup tool for order-status questions.
                If the order ID is missing or ambiguous, ask for clarification.
                Report only facts supplied by the tool.
                PROCESSING means the order is being processed;
                no shipping date or estimate is available.
                SHIPPED means the order has shipped.
                NOT_FOUND means the lookup found no matching order;
                the reason is unknown.
                Do not invent shipping estimates or reasons for missing orders.
                """
            ));

            try (HttpClient client = HttpClient.newHttpClient()) {

                while (true) {

                    System.out.print("Enter your question (or /bye to exit): ");

                    if (!scanner.hasNextLine()) {
                        break;
                    }

                    String question = scanner.nextLine();

                    if (question.equalsIgnoreCase("/bye")) {
                        System.out.println("Goodbye!");
                        break;
                    }

                    if (question.isBlank()) {
                        continue;
                    }

                    messages.add(Map.of(
                            "role", "user",
                            "content", question
                    ));

                    Map<String, Object> requestData = Map.of(
                            "model", "qwen3:4b-instruct",
                            "messages", messages,
                            "tools", List.of(orderStatusTool()),
                            "stream", false,
                            "think", false
                    );

                    String requestBody = objectMapper.writeValueAsString(requestData);
                    System.out.println("Request JSON: " + requestBody);


                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:11434/api/chat"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

                    HttpResponse<String> response = client.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

                    System.out.println("HTTP status: " + response.statusCode());
                    System.out.println("Response JSON: " + response.body());

                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
                    }

                    JsonNode root = objectMapper.readTree(response.body());

                    Map<?, ?> assistantMessage = objectMapper.treeToValue(
                            root.path("message"),
                            Map.class
                    );
                    messages.add(assistantMessage);

                    JsonNode toolCalls = root.path("message").path("tool_calls");

                    // No tool requested: display the model's answer and finish.
                    if (toolCalls.isMissingNode() || (toolCalls.isArray() && toolCalls.isEmpty())) {

                        String answer = root.path("message")
                                .path("content")
                                .asString("");

                        System.out.println("Model answer: " + answer);
                        continue;
                    }

                    if (!toolCalls.isArray()) {
                        throw new IllegalStateException("Expected tool_calls to be an array.");
                    }

                    for (JsonNode toolCall : toolCalls) {

                        JsonNode function = toolCall.path("function");

                        String toolName = function.path("name").asString("");

                        if (!"getOrderStatus".equals(toolName)) {
                            throw new IllegalArgumentException("Unknown tool: " + toolName);
                        }

                        JsonNode orderIdNode = function.path("arguments").path("orderId");

                        if (!orderIdNode.isString() || orderIdNode.asString().isBlank()) {
                            throw new IllegalArgumentException(
                                    "getOrderStatus requires a nonblank string orderId."
                            );
                        }

                        String orderIdInResponse = orderIdNode.asString();
                        String toolResult = getOrderStatus(orderIdInResponse);

                        System.out.println("Executed tool: " + toolName);
                        System.out.println("Order ID: " + orderIdInResponse);
                        System.out.println("Tool result: " + toolResult);

                        Map<String, Object> toolMessage = Map.of(
                                "role", "tool",
                                "tool_name", toolName,
                                "content", toolResult
                        );

                        messages.add(toolMessage);
                    }

                    Map<String, Object> followUpData = Map.of(
                            "model", "qwen3:4b-instruct",
                            "messages", messages,
                            "stream", false,
                            "think", false
                    );

                    String followUpBody = objectMapper.writeValueAsString(followUpData);
                    System.out.println("Follow-up JSON: " + followUpBody);

                    HttpRequest followUpRequest = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:11434/api/chat"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(followUpBody))
                            .build();

                    HttpResponse<String> finalResponse = client.send(
                            followUpRequest,
                            HttpResponse.BodyHandlers.ofString()
                    );

                    if (finalResponse.statusCode() != 200) {
                        throw new IllegalStateException(
                                "Ollama returned HTTP " + finalResponse.statusCode()
                        );
                    }

                    JsonNode finalRoot = objectMapper.readTree(finalResponse.body());

                    messages.add(objectMapper.treeToValue(
                            finalRoot.path("message"),
                            Map.class
                    ));

                    String answer = finalRoot.path("message")
                            .path("content")
                            .asString("");

                    System.out.println("Model answer: " + answer);

                }
            }
        }
    }

package org.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class OllamaClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        // This list exists for the duration of the conversation.
        List<Map<String, Object>> messages = new ArrayList<>();

//        messages.add(Map.of(
//                "role", "system",
//                "content", "Answer each question in at most two short sentences."
//        ));

        // Create one client and reuse it for all requests in this conversation.
        try (HttpClient client = HttpClient.newHttpClient()) {

            while (true) {

                System.out.print("Enter your question (or /bye to exit): ");
                String question = scanner.nextLine();

                if (question.equalsIgnoreCase("/bye")) {
                    System.out.println("Goodbye!");
                    break;
                }

                Map<String, Object> userMessage = Map.of(
                        "role", "user",
                        "content", question
                );

                messages.add(userMessage);

                Map<String, Object> requestData = Map.of(
                        "model", "qwen3:4b-instruct",
                        "messages", messages,
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

                JsonNode root = objectMapper.readTree(response.body());

                String answer = root.path("message")
                        .path("content")
                        .asText();

                System.out.println("Model answer: " + answer);

                int inputTokens = root.path("prompt_eval_count").asInt();
                int outputTokens = root.path("eval_count").asInt();

                System.out.println("Input tokens: " + inputTokens);
                System.out.println("Output tokens: " + outputTokens);

                Map<String, Object> assistantMessage = Map.of(
                        "role", "assistant",
                        "content", answer
                );

                messages.add(assistantMessage);
            }
        }
    }
}
package org.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Iterator;
import java.util.stream.Stream;

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
                        "stream", true,
                        "think", false,
                        "options", Map.of(
                                "temperature", 0.7,
                                "top_k", 20,
                                "top_p", 0.8
                        )
                );

                String requestBody = objectMapper.writeValueAsString(requestData);

                System.out.println("Request JSON: " + requestBody);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:11434/api/chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();


                long start = System.nanoTime();

                HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

                StringBuilder fullAnswer = new StringBuilder();
                JsonNode completion = null;

                long firstContentNanos = -1;

                try (Stream<String> lines = response.body()) {

                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
                    }

                    Map<String, Object> optionsMap = (Map<String, Object>)requestData.get("options");

                    Double temperature = (Double)optionsMap.get("temperature");
                    Integer topK = (Integer)optionsMap.get("top_k");
                    Double topP = (Double)optionsMap.get("top_p");
                    System.out.print("Model answer with temperature : "+temperature+", top_k : "+topK+", topP : "+topP);
                    System.out.println();

                    Iterator<String> iterator = lines.iterator();

                    while (iterator.hasNext()) {

                        String line = iterator.next();

                        if (line.isBlank()) {
                            continue;
                        }

                        JsonNode chunk = objectMapper.readTree(line);

                        if (chunk.has("error")) {
                            throw new IllegalStateException(chunk.path("error").asString());
                        }

                        String text = chunk.path("message")
                                .path("content")
                                .asString("");

                        if (firstContentNanos == -1 && !text.isEmpty()) {
                            firstContentNanos = System.nanoTime() - start;
                        }


                        // Display this piece immediately.
                        System.out.print(text);
                        System.out.flush();

                        // Preserve it for the next conversation turn.
                        fullAnswer.append(text);

                        if (chunk.path("done").asBoolean()) {
                            completion = chunk;
                            break;
                        }
                    }
                }

                System.out.println();

                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                if (firstContentNanos != -1) {
                    Duration firstContentTime = Duration.ofNanos(firstContentNanos);

                    System.out.println("Time to first content in milliseconds: "+ firstContentTime.toMillis());
                }

                System.out.println("Total response time in milliseconds: " + elapsed.toMillis());

                if (completion == null) {
                    throw new IllegalStateException("The response ended without a completion message.");
                }

                Duration modelLoadTime = Duration.ofNanos(completion.path("load_duration").asLong());

                Duration inputProcessingTime = Duration.ofNanos(completion.path("prompt_eval_duration").asLong());

                Duration outputGenerationTime = Duration.ofNanos(completion.path("eval_duration").asLong());

                Duration ollamaTotalTime = Duration.ofNanos(completion.path("total_duration").asLong());

                System.out.println("Model loading in milliseconds: " + modelLoadTime.toMillis());
                System.out.println("Input processing in milliseconds: " + inputProcessingTime.toMillis());
                System.out.println("Output generation in milliseconds: " + outputGenerationTime.toMillis());
                System.out.println("Ollama total in milliseconds: " + ollamaTotalTime.toMillis());

                System.out.println("Input tokens: " + completion.path("prompt_eval_count").asInt());
                System.out.println("Output tokens: " + completion.path("eval_count").asInt());

                Map<String, Object> assistantMessage = Map.of(
                        "role", "assistant",
                        "content", fullAnswer.toString()
                );

                messages.add(assistantMessage);
            }
        }
    }
}
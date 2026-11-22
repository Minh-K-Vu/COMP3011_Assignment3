package com.example.speechtotext.service;
import com.example.speechtotext.service.TokenTracker;
import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestClient;
//Replace RestClient with WebClient to allow asynchronous HTTP calls.
import org.springframework.web.reactive.function.client.WebClient;
//Use Mono for asynchronous
import reactor.core.publisher.Mono;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.example.speechtotext.dto.OpenAIResponse;

import java.util.Map;
import java.util.concurrent.atomic.DoubleAdder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

@Service
public class TranscriptionService {
	
	private WebClient webClient;
	private TokenTracker tokenTracker;
	// Track total subtitle audio processed in seconds.
	private final DoubleAdder subtitleUsage = new DoubleAdder();
	
	public TranscriptionService(
			//Let Spring construct RestClient
            WebClient.Builder restClientBuilder,
            //Read openai.api-key and store in apiKey
            @Value("${openai.api-key}") String apiKey,
            //Read openai.base-url and store in baseUrl
            @Value("${openai.base-url}") String baseUrl,
            TokenTracker tokenTracker) {
		
		this.tokenTracker = tokenTracker;
		
		//using restClientBuilder we set:
		//baseUrl = https://api.openai.com
		//all requests will have header:
		//Authorization, Bearer + apiKey
		
		HttpClient httpClient = HttpClient.create()
		        .proxyWithSystemProperties();

		webClient = restClientBuilder
		        .clientConnector(new ReactorClientHttpConnector(httpClient))
		        .baseUrl(baseUrl)
		        .defaultHeader("Authorization", "Bearer " + apiKey)
		        .build();

		// Log configuration status, never the API key itself.
		System.err.println("HTTPS proxy configured: "
		        + (System.getProperty("https.proxyHost") != null));

		System.err.println("OpenAI key configured: "
		        + (apiKey != null && !apiKey.isBlank()));
       
    }
	
	
	// Get a file as input, post request to OpenAi and return OpenAi response
	public Mono<String> transcribe(MultipartFile file) {
		try {
			//Convert file into a resource. RestClient can work with resource.
			ByteArrayResource audioResource =
	                new ByteArrayResource(file.getBytes()) { //get raw bytes of file
	            @Override
	            public String getFilename() {
	                String originalFilename =
	                        file.getOriginalFilename();
	                if (originalFilename != null) {
	                    return originalFilename;
	                }
	                return "recording.webm";
	            }
	        };
	        //Create a map that stores data requests needed to send to OpenAi
	        //MultiValueMap supports resource so we can send the file.
	        //OpenAi needs to receive a file and the model.
	        MultiValueMap<String, Object> requestBody =
	                new LinkedMultiValueMap<>();
	        //File
	        requestBody.add(
	                "file",
	                audioResource
	        );
	        //AI model
	        requestBody.add(
	                "model",
	                "gpt-4o-mini-transcribe"
	        );
	        //Post request to send to OpenAi
	        return webClient.post()
                    .uri("/v1/audio/transcriptions") //URL request
                    .contentType(
                            MediaType.MULTIPART_FORM_DATA //set content being sent to multipart form data which support multivaluemap
                    )
                    //body request
                    .bodyValue(requestBody)
                    .retrieve() // get the response
                    .bodyToMono(OpenAIResponse.class)
                    .switchIfEmpty( //if body is empty return error
                            Mono.error(
                                    new IllegalStateException(
                                            "OpenAI did not respond"
                                    )
                            )
                    )
                    .map(response -> {
                        // Add usage
                        if (response.usage() != null) {

                            tokenTracker.addUsage(
                                    response.usage().input_tokens(),
                                    response.usage().output_tokens()
                            );
                        }
                        return response.text();
                    });
		} catch (IOException error) {
			// Change from string to Mono for error handling
	        return Mono.error(
	                new IllegalStateException(
	                        "There was a problem reading the uploaded file",
	                        error
	                )
	        ); 
		}
	}
	// Get subtitle text and timestamps from Whisper.
	public Mono<Map<String, Object>> transcribeSubtitles(MultipartFile file) {
	    try {
	        ByteArrayResource audio = new ByteArrayResource(file.getBytes()) {
	            @Override
	            public String getFilename() {
	                return file.getOriginalFilename() != null
	                        ? file.getOriginalFilename()
	                        : "audio.mp3";
	            }
	        };

	        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

	        body.add("file", audio);
	        body.add("model", "whisper-1");
	        body.add("response_format", "verbose_json");
	        body.add("timestamp_granularities[]", "word");
	        body.add("timestamp_granularities[]", "segment");

	        // Request the subtitle data asynchronously.
	        return webClient.post()
	                .uri("/v1/audio/transcriptions")
	                .contentType(MediaType.MULTIPART_FORM_DATA)
	                .bodyValue(body)
	                .retrieve()
	                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
	                .doOnNext(response -> {
	                    // Count audio duration after a successful request.
	                    if (response.get("duration") instanceof Number duration) {
	                        subtitleUsage.add(duration.doubleValue());
	                    }
	                });

	    } catch (IOException e) {
	        return Mono.error(e);
	    }
	}

	// Return total subtitle transcription usage.
	public double getSubtitleUsage() {
	    return subtitleUsage.sum();
	}
}

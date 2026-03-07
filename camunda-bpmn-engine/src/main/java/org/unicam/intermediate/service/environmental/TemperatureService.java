package org.unicam.intermediate.service.environmental;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.pojo.Place;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@AllArgsConstructor
public class TemperatureService {

    private final EnvironmentDataService environmentDataService;

    public double fetchTemperatureFromEndpoint(String endpoint) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        int status = con.getResponseCode();
        if (status != 200) {
            throw new IllegalStateException("HTTP status " + status);
        }

        try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            StringBuilder content = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }

            String response = content.toString().replaceAll("[\\[\\]]", "").trim();
            return Double.parseDouble(response);
        }
    }

    public double getTemperatureFromPlace(String placeId){
        try {
            Optional<Place> maybePlace = environmentDataService.getData() // Use service
                    .getPlaces()
                    .stream()
                    .filter(p -> p.getId().equals(placeId))
                    .findFirst();

            if (maybePlace.isEmpty()) throw new IllegalStateException("Place with id " + placeId + " not found");

            Place place = maybePlace.get();

            Map<String, Object> attributes = place.getAttributes();
            if (attributes == null || !attributes.containsKey("temperature")) {
                throw new IllegalStateException("Temperature attribute not found for place with id " + placeId + " not found");
            }

            String temperature = attributes.get("temperature").toString();

            if (temperature.startsWith("http://") || temperature.startsWith("https://") || temperature.isBlank()) {
                return fetchTemperatureFromEndpoint(temperature);
            } else {
                return Double.parseDouble(temperature);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}

// src/main/java/org/unicam/intermediate/models/pojo/Place.java

package org.unicam.intermediate.models.pojo;

import lombok.Getter;
import lombok.Setter;
import org.unicam.intermediate.models.environmental.LocationArea;

import java.util.List;
import java.util.Map;

@Setter
@Getter
public class Place {
    private String id;
    private String name;
    private String temperature;
    private List<List<Double>> coordinates;
    private Map<String, Object> attributes;



    private transient LocationArea locationArea;

    public LocationArea getLocationArea() {
        if (locationArea == null && coordinates != null && !coordinates.isEmpty()) {
            locationArea = new LocationArea(coordinates);
        }
        return locationArea;
    }
}
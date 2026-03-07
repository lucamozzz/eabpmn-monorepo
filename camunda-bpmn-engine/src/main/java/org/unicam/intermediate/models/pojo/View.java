package org.unicam.intermediate.models.pojo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Setter
@Getter
public class View {
    private String id;
    private String name;
    private List<String> logicalPlaces;
    private Map<String, Object> attributes;
}

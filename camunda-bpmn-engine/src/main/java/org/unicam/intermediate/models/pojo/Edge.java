package org.unicam.intermediate.models.pojo;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Setter
@Getter
public class Edge {
    private String id;
    private String name;
    private String source;
    private String target;
    private Map<String, Object> attributes;

}

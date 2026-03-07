package org.unicam.intermediate.utils;

import org.camunda.bpm.engine.impl.util.xml.Namespace;

public class Constants {

    public static final Namespace SPACE_NS = new Namespace("http://space" );

    public static final String movementExecutionListenerBeanName = "movementExecutionListener";
    public static final String unbindingExecutionListenerBeanName = "unbindingExecutionListener";
    public static final String bindingExecutionListenerBeanName = "bindingExecutionListener";

}

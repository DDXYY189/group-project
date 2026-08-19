package com.example.demo.tool;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 一个可以被大模型调用的工具。
 *
 * <p>{@link #parametersSchema()} 返回的是描述函数签名的 JSON Schema，模型靠它
 * 知道工具的名称、用途、需要哪些参数，从而生成正确的调用参数。
 */
public interface Tool {

    /** 工具名称，会作为 function.name 暴露给大模型。 */
    String name();

    /** 工具用途说明，帮助大模型判断什么时候该调用它。 */
    String description();

    /** 参数的 JSON Schema，描述入参对象的结构。 */
    Map<String, Object> parametersSchema();

    /** 真正执行工具，arguments 是大模型生成的参数。返回工具执行结果文本。 */
    String execute(JsonNode arguments);
}

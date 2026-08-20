package com.uniselect.cs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * UniSelect 客服 Agent（代码实现版）启动类。
 * 后续 Step 将在此工程内逐步接入意图识别、RAG、LLM 流式与上下文管理。
 */
@SpringBootApplication
public class CsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CsAgentApplication.class, args);
    }
}

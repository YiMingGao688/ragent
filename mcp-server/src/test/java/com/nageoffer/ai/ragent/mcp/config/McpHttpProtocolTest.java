/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.config;

import com.nageoffer.ai.ragent.mcp.dao.mapper.AfterSaleMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderItemMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderMapper;
import com.nageoffer.ai.ragent.mcp.dao.result.AfterSaleDetailResult;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.bit.BitAfterSaleQueryMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.bit.BitOrderQueryMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.common.CurrentDateMcpExecutor;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = McpHttpProtocolTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class McpHttpProtocolTest {

    @LocalServerPort
    private int port;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private AfterSaleMapper afterSaleMapper;

    @Test
    void shouldDiscoverAndCallToolsWithUserMetaOverHttp() {
        try (var client = McpClientBuilder.create("ragent-test")
                .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));
            assertThat(client.listTools().block(Duration.ofSeconds(5)))
                    .extracting(tool -> tool.name())
                    .contains("current_date", "query_order");

            CallToolResult date = client.callTool("current_date", Map.of("timezone", "UTC"))
                    .block(Duration.ofSeconds(5));
            assertThat(date.isError()).isFalse();
            assertThat(((TextContent) date.content().get(0)).text()).contains("当前日期");

            CallToolResult missingIdentity = client.callTool("query_order", Map.of())
                    .block(Duration.ofSeconds(5));
            assertThat(missingIdentity.isError()).isTrue();
            assertThat(((TextContent) missingIdentity.content().get(0)).text()).contains("无法确认你的身份");

            CallToolResult order = client.callTool("query_order", Map.of("orderNo", "NO-1"),
                    Map.of(McpToolResults.META_USER_ID, "user-1"))
                    .block(Duration.ofSeconds(5));
            assertThat(order.isError()).isFalse();
            assertThat(((TextContent) order.content().get(0)).text()).contains("未找到订单 NO-1");
            verify(orderMapper).selectByNo("NO-1", "user-1");
        }
    }

    /**
     * 查到售后单时，AgentScope 客户端同时收到模型正文和供终答使用的同一份状态说明
     */
    @Test
    void shouldReturnAfterSaleLegendInTextAndMetadataOverHttp() {
        AfterSaleDetailResult row = new AfterSaleDetailResult();
        row.setAfterSaleNo("AS-1");
        row.setOrderNo("NO-1");
        row.setType("退货退款");
        row.setStatus("处理中");
        when(afterSaleMapper.selectDetails("user-1", "AS-1", null, 20)).thenReturn(List.of(row));

        try (var client = McpClientBuilder.create("ragent-test")
                .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));
            Map<String, Object> meta = Map.of(McpToolResults.META_USER_ID, "user-1");
            CallToolResult found = client.callTool("query_after_sale", Map.of("afterSaleNo", "AS-1"), meta)
                    .block(Duration.ofSeconds(5));
            assertThat(found.isError()).isFalse();
            String text = ((TextContent) found.content().get(0)).text();
            String legend = text.lines().skip(1).findFirst().orElseThrow();
            assertThat(text).contains("当前状态: 处理中");
            assertThat(legend).contains("待处理 = 等待人工审核", "处理中 = 审核已通过", "退款发起", "已完成 = 处理结束");
            assertThat(found.meta()).containsEntry("com.nageoffer.ragent/afterSaleStatusLegend", legend);

            CallToolResult missing = client.callTool("query_after_sale", Map.of("afterSaleNo", "AS-2"), meta)
                    .block(Duration.ofSeconds(5));
            assertThat(missing.isError()).isFalse();
            assertThat(((TextContent) missing.content().get(0)).text()).contains("未找到售后单 AS-2");
            assertThat(missing.meta()).isNullOrEmpty();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import(McpServerConfig.class)
    static class Application {

        @Bean
        OrderMapper orderMapper() {
            return mock(OrderMapper.class);
        }

        @Bean
        OrderItemMapper orderItemMapper() {
            return mock(OrderItemMapper.class);
        }

        @Bean
        AfterSaleMapper afterSaleMapper() {
            return mock(AfterSaleMapper.class);
        }

        @Bean
        McpServerFeatures.SyncToolSpecification currentDateToolSpecification() {
            return new CurrentDateMcpExecutor().currentDateToolSpecification();
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryOrderToolSpecification(
                OrderMapper orderMapper, OrderItemMapper orderItemMapper) {
            return new BitOrderQueryMcpExecutor(orderMapper, orderItemMapper).queryOrderToolSpecification();
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryAfterSaleToolSpecification(AfterSaleMapper afterSaleMapper) {
            return new BitAfterSaleQueryMcpExecutor(afterSaleMapper).queryAfterSaleToolSpecification();
        }
    }
}

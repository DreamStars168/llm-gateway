package com.zhouyu.gateway.protocol.sse;

import java.util.ArrayList;
import java.util.List;

/**
 * SSE 帧解析器。
 *
 * <p>SSE 规约：事件以空行分隔，同一事件可有多行 {@code data:}，需拼接。
 * 网络分片可能把一帧切在任意位置，因此必须自己缓冲重组。
 *
 * <p><b>有状态</b>，每请求一个实例。
 */
public class SseFrameParser {

    private final StringBuilder buffer = new StringBuilder();
    private String currentEvent = null;
    private final List<String> currentData = new ArrayList<>();

    /**
     * 喂入一段字节（已按 UTF-8 解码），返回本次可完整解析出的事件。
     *
     * @param chunk 新到达的文本
     * @return 解析完成的事件列表（可能为空）
     */
    public List<SseEvent> feed(String chunk) {
        buffer.append(chunk);
        List<SseEvent> events = new ArrayList<>();

        while (true) {
            int nl = buffer.indexOf("\n");
            if (nl < 0) {
                break;
            }
            String line = buffer.substring(0, nl);
            // 去掉 CRLF 的 \r
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            // 消费掉该行及其换行符
            buffer.delete(0, nl + 1);

            if (line.isEmpty()) {
                // 空行 = 事件边界
                if (!currentData.isEmpty() || currentEvent != null) {
                    events.add(new SseEvent(currentEvent, String.join("\n", currentData)));
                }
                currentEvent = null;
                currentData.clear();
                continue;
            }

            if (line.startsWith(":")) {
                continue;
            }

            int colon = line.indexOf(':');
            String field;
            String value;
            if (colon < 0) {
                field = line;
                value = "";
            } else {
                field = line.substring(0, colon);
                value = line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
            }

            switch (field) {
                case "event" -> currentEvent = value;
                case "data" -> currentData.add(value);
                default -> { }
            }
        }
        return events;
    }

    /**
     * 流结束时，缓冲区可能残留一个没有以空行收尾的事件，需要 flush 出去。
     */
    public List<SseEvent> flush() {
        List<SseEvent> events = new ArrayList<>();
        if (!buffer.isEmpty()) {
            String rest = buffer.toString();
            buffer.setLength(0);
            for (String line : rest.split("\r?\n")) {
                if (line.startsWith("data:")) {
                    currentData.add(line.substring(5).trim());
                }
            }
        }
        if (!currentData.isEmpty() || currentEvent != null) {
            events.add(new SseEvent(currentEvent, String.join("\n", currentData)));
            currentEvent = null;
            currentData.clear();
        }
        return events;
    }

    private static int findLineBreak(CharSequence sb) {
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 一个 SSE 事件。
     *
     * @param event 事件名，可能为 null（只有 data）
     * @param data  拼接后的 data 内容
     */
    public record SseEvent(String event, String data) {
    }
}

package com.quant.stock.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 会话引擎事件（脚手架/调试用）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionEvent {
    private String time;
    private String type;
    private String branch;
    private String detail;
}

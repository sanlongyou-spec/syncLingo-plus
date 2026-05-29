package com.si.backend.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Text response returned to the Teams Bot chat.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamsBotQueryResponse {

    private String replyText;

    private String responseType;

    private List<TeamsBotQuerySourceVo> sources;

    private Boolean userMatched;

    private Long userId;

    private String command;
}

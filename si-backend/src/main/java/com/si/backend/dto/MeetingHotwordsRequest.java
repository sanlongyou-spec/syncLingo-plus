package com.si.backend.dto;

import lombok.Data;

import java.util.List;

/**
 * Request to add meeting participant names (and optional venue) as ASR hotwords,
 * e.g. after the Teams Bot pulls the participant list.
 */
@Data
public class MeetingHotwordsRequest {

    private List<String> names;

    private String venue;
}

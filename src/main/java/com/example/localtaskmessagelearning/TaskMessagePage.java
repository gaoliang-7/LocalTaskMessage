package com.example.localtaskmessagelearning;

import java.util.List;

public record TaskMessagePage(
        List<TaskMessageView> items,
        String nextBeforeId
) {}

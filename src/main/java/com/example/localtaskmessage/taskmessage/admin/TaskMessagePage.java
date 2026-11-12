package com.example.localtaskmessage.taskmessage.admin;

import java.util.List;

public record TaskMessagePage(
        List<TaskMessageView> items,
        String nextBeforeId
) {}

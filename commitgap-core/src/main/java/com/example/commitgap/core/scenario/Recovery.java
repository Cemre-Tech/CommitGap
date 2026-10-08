package com.example.commitgap.core.scenario;

import com.example.commitgap.core.model.RecoveryAction;

public record Recovery(RecoveryAction action) {

    public static Recovery none() {
        return new Recovery(RecoveryAction.NONE);
    }
}

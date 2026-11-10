package com.example.medibook.airquality;

import java.io.IOException;

public final class AirQualityException extends IOException {
    public enum Kind { NETWORK, HTTP, FORMAT }
    public final Kind kind;
    public AirQualityException(Kind kind) { super(kind.name()); this.kind = kind; }
}

package com.viun.core;

import java.util.Map;
import java.util.Set;

public record UpdateFilter(Map<String, Set<String>> unionGraphNames) {
}

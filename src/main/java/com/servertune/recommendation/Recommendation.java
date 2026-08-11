package com.servertune.recommendation;

import java.util.List;

/**
 * One suggestion for the server owner, with the measurement that prompted it.
 *
 * <p>A recommendation is text. Nothing in this package writes to config.yml, enables a
 * module, or schedules a task - acting on a recommendation is the owner's decision, taken
 * by editing config.yml themselves.
 *
 * @param priority     how strongly the measurement suggests this
 * @param title        the suggestion, imperative and short
 * @param evidence     what was measured that led here; never a causal claim
 * @param configPath   the config.yml key the owner would edit, or null if there is none
 * @param gameplayNote what changes for players if they do it, or null when nothing does
 */
public record Recommendation(Priority priority,
                             String title,
                             List<String> evidence,
                             String configPath,
                             String gameplayNote) {

    public enum Priority {
        LOW,
        MEDIUM,
        HIGH;

        public boolean atLeast(Priority other) {
            return ordinal() >= other.ordinal();
        }
    }

    public Recommendation(Priority priority, String title, List<String> evidence,
                          String configPath, String gameplayNote) {
        this.priority = priority;
        this.title = title;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        this.configPath = configPath;
        this.gameplayNote = gameplayNote;
    }

    public boolean hasConfigPath() {
        return configPath != null && !configPath.isEmpty();
    }

    public boolean hasGameplayNote() {
        return gameplayNote != null && !gameplayNote.isEmpty();
    }

    public static Builder builder(Priority priority, String title) {
        return new Builder(priority, title);
    }

    public static final class Builder {
        private final Priority priority;
        private final String title;
        private final List<String> evidence = new java.util.ArrayList<>();
        private String configPath;
        private String gameplayNote;

        private Builder(Priority priority, String title) {
            this.priority = priority;
            this.title = title;
        }

        public Builder evidence(String line) {
            this.evidence.add(line);
            return this;
        }

        public Builder configPath(String path) {
            this.configPath = path;
            return this;
        }

        public Builder gameplayNote(String note) {
            this.gameplayNote = note;
            return this;
        }

        public Recommendation build() {
            return new Recommendation(priority, title, evidence, configPath, gameplayNote);
        }
    }
}

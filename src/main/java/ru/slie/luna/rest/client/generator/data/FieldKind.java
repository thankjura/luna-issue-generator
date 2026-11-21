package ru.slie.luna.rest.client.generator.data;

import java.util.Arrays;
import java.util.Optional;

/**
 * Custom field types supported by the generator.
 * {@code controlled} types get control values with a known number of issues to check the search;
 * types without a searcher in Luna (color, duration) are only filled.
 */
public enum FieldKind {
    STRING("string", "StringFieldType", "Строка", true),
    TEXT("text", "TextFieldType", "Текст", true),
    INTEGER("integer", "IntegerFieldType", "Целое", true),
    DOUBLE("double", "DoubleFieldType", "Дробное", true),
    DATE("date", "DateFieldType", "Дата", true),
    DATE_TIME("dateTime", "DateTimeFieldType", "Дата-время", true),
    BOOLEAN("boolean", "BooleanFieldType", "Флаг", false),
    SINGLE_SELECT("singleSelect", "SingleSelectFieldType", "Выбор", true),
    MULTI_SELECT("multiSelect", "MultiSelectFieldType", "Мультивыбор", true),
    USER("user", "UserFieldType", "Пользователь", true),
    MULTI_USER("multiUser", "MultiUserFieldType", "Пользователи", true),
    LABEL("label", "LabelFieldType", "Метки", true),
    ISSUE_REF("issueRef", "IssueRefFieldType", "Ссылка", true),
    MULTI_ISSUE_REF("multiIssueRef", "MultiIssueRefFieldType", "Ссылки", true),
    PRIORITY("priority", "PriorityFieldType", "Приоритет", false),
    COLOR("color", "ColorFieldType", "Цвет", false),
    DURATION("duration", "DurationFieldType", "Длительность", false);

    private static final String TYPE_PACKAGE = "ru.slie.luna.issue.field.type.";

    private final String configName;
    private final String typeKey;
    private final String label;
    private final boolean controlled;

    FieldKind(String configName, String typeClass, String label, boolean controlled) {
        this.configName = configName;
        this.typeKey = TYPE_PACKAGE + typeClass;
        this.label = label;
        this.controlled = controlled;
    }

    public String configName() {
        return configName;
    }

    public String typeKey() {
        return typeKey;
    }

    public String label() {
        return label;
    }

    /** Has unique control values with an exactly known number of issues (needs a searcher on the server too). */
    public boolean controlled() {
        return controlled;
    }

    public boolean hasOptions() {
        return this == SINGLE_SELECT || this == MULTI_SELECT;
    }

    public static Optional<FieldKind> byConfigName(String name) {
        String normalized = name.replace("_", "").replace("-", "");
        return Arrays.stream(values()).filter(k -> k.configName.equalsIgnoreCase(normalized)).findFirst();
    }
}

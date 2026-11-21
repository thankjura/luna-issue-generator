package ru.slie.luna.rest.client.generator.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Everything created so far. Saved to state.json after each step and each issue batch,
 * so an interrupted run continues where it stopped instead of creating duplicates.
 */
public class GeneratorState {
    public String server;
    public long seed;
    /** Random tag of this data set: keeps control values of system fields unique between runs on one server. */
    public String runTag;
    public List<UserRecord> users = new ArrayList<>();
    /** Users only used as control values of user fields, never as random values. */
    public List<UserRecord> controlUsers = new ArrayList<>();
    public List<ProjectRecord> projects = new ArrayList<>();
    public List<FieldRecord> fields = new ArrayList<>();
    /** Issues referenced by control values of issue reference fields. */
    public List<String> anchorIssues = new ArrayList<>();
    /** Control values of system fields (summary). */
    public List<ControlValue> systemControls = new ArrayList<>();
    /** Issue sequence numbers below this one are done (created or failed). */
    public long issuesDone;
    /** Issues at or above {@link #issuesDone} already finished when a run was stopped in the middle of a batch. */
    public List<Long> doneAhead = new ArrayList<>();
    /** Custom field id -> created issues having a value of the field (expected result of "is not EMPTY"). */
    public Map<String, Long> fieldFilled = new HashMap<>();
    /** False for a state written before {@link #fieldFilled} was tracked: its counts miss the earlier issues. */
    public boolean fieldFilledComplete;
    public long issuesCreated;
    public long issuesFailed;
    public double issueSeconds;
    public StepTimes stepSeconds = new StepTimes();

    public static class StepTimes {
        public double users;
        public double projects;
        public double fields;
        public double issues;
    }

    public static class UserRecord {
        public String login;
        public String email;
        public String displayName;
        public String password;

        public UserRecord() {
        }

        public UserRecord(String login, String email, String displayName, String password) {
            this.login = login;
            this.email = email;
            this.displayName = displayName;
            this.password = password;
        }
    }

    public static class ProjectRecord {
        public long id;
        public String key;
        public String name;
        public List<Long> issueTypeIds = new ArrayList<>();
        public List<Long> priorityIds = new ArrayList<>();
    }

    public static class FieldRecord {
        public String id;
        public String name;
        public FieldKind kind;
        /** Searcher key, null if the field type has no searcher. */
        public String searcher;
        public List<Long> optionIds = new ArrayList<>();
        public List<ControlValue> controls = new ArrayList<>();
    }

    /**
     * A unique value of a field given to a known set of issues (sequence numbers).
     * {@code query} is a QS fragment that must find exactly the issues created with this value.
     */
    public static class ControlValue {
        public int index;
        /** Value in the issue create request format. */
        public Object value;
        public String query;
        public List<Long> seqs = new ArrayList<>();
    }
}

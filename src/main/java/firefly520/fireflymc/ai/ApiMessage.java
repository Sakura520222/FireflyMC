package firefly520.fireflymc.ai;

public record ApiMessage(String role, String name, String content) {
    public ApiMessage {
        if (role == null) {
            role = "user";
        }
    }
}

package com.tanuki276.fpssc;

/** Decision output used by the Android input layer. */
record CombatCommand(
        AgentCore.Vec2 move,
        AgentCore.Vec2 aim,
        boolean fire,
        boolean superAttack,
        boolean hypercharge,
        float hitChance,
        String reason
) {
    static CombatCommand idle(String reason) {
        return new CombatCommand(new AgentCore.Vec2(0, 0), new AgentCore.Vec2(0, 0),
                false, false, false, 0f, reason);
    }

    static CombatCommand fromLegacy(AgentCore.ControlCommand command) {
        return new CombatCommand(command.move(), command.aim(), command.fire(), command.superAttack(),
                false, command.fire() ? 0.5f : 0f, command.reason());
    }

    AgentCore.ControlCommand legacy() {
        return new AgentCore.ControlCommand(move, aim, fire, superAttack, reason);
    }
}

package top.rymc.phira.main.game.exception;

import lombok.Getter;

@Getter
public class GameOperationException extends RuntimeException {

    private final String messageKey;

    public GameOperationException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public static GameOperationException invalidState() {
        return new GameOperationException("error.invalid_state");
    }

    public static GameOperationException permissionDenied() {
        return new GameOperationException("error.permission_denied");
    }

    public static GameOperationException roomFull() {
        return new GameOperationException("error.room_full");
    }

    public static GameOperationException roomLocked() {
        return new GameOperationException("error.room_locked");
    }

    public static GameOperationException roomNotFound() {
        return new GameOperationException("error.room_not_found");
    }

    public static GameOperationException roomAlreadyExists() {
        return new GameOperationException("error.room_already_exists");
    }

    /** Spelled out rather than a message key: the cap is an operator setting, not a game rule. */
    public static GameOperationException roomLimitReached(int maxRooms) {
        return new GameOperationException("服务器房间数已达上限（" + maxRooms + "），无法继续创建。");
    }

    public static GameOperationException chartNotSelected() {
        return new GameOperationException("error.chart_not_selected");
    }

    public static GameOperationException chartNotFound() {
        return new GameOperationException("error.chart_not_found");
    }

    public static GameOperationException chatNotEnabled() {
        return new GameOperationException("error.chat_not_enabled");
    }

    public static GameOperationException recordNotFound() {
        return new GameOperationException("error.record_not_found");
    }

    public static GameOperationException recordSubmitFailed() {
        return new GameOperationException("成绩提交失败，请稍后重试");
    }

    public static GameOperationException chartPoolUnavailable() {
        return new GameOperationException("当前不在投票阶段，无法查看谱池状态。");
    }

    public static GameOperationException alreadyInRoom() {
        return new GameOperationException("error.already_in_room");
    }

    public static GameOperationException notInRoom() {
        return new GameOperationException("error.not_in_room");
    }

    public static GameOperationException notHost() {
        return new GameOperationException("error.not_host");
    }

    public static GameOperationException playerNotFound() {
        return new GameOperationException("error.player_not_found");
    }

    public static GameOperationException sessionExpired() {
        return new GameOperationException("error.session_expired");
    }

    public static GameOperationException authenticationFailed() {
        return new GameOperationException("error.authentication_failed");
    }
}

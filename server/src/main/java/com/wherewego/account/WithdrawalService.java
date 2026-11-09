package com.wherewego.account;

import com.wherewego.room.RoomService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴.
 *
 * <p>방에서 빠지는 일과 계정을 지우는 일이 <b>한 트랜잭션</b>이어야 해서 따로 둔다.
 * 둘로 나누어 부르면 앞은 되고 뒤는 실패했을 때 "모든 방에서 나갔는데 계정은 살아 있는"
 * 사람이 남는다.
 *
 * <p>{@link AccountService} 가 아니라 여기 있는 것은 방향 때문이다. 방이 계정을 알고
 * 있으므로({@code RoomService -> AccountService}) 계정이 방을 다시 알면 서로 물린다.
 * 이 클래스는 둘 다 알지만 아무도 이것을 모른다.
 */
@Service
public class WithdrawalService {

    private final RoomService rooms;
    private final AccountRepository accounts;

    public WithdrawalService(RoomService rooms, AccountRepository accounts) {
        this.rooms = rooms;
        this.accounts = accounts;
    }

    /**
     * 들어가 있던 방에서 모두 빠지고, 계정을 익명으로 만든다.
     *
     * <p>방에서 빠지는 규칙은 "방 나가기"와 똑같다 — 방장이던 방은 가장 먼저 들어온
     * 참가자에게 넘어가고, 혼자 쓰던 방은 지워진다. 담아 둔 후보는 남는다.
     */
    @Transactional
    public void withdraw(long userId) {
        rooms.leaveAllRooms(userId);
        accounts.anonymize(userId);
    }
}

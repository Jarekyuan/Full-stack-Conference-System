package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.dto.MeetingUpdateDTO;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingRoomMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预约并发防护测试（依赖本地 MySQL + Redis）
 *
 * ① 跨路径互斥：create() 有分布式锁，update()（T2.10 改期）若不经过那把锁，
 *    "改期"与"新建"就能各自通过冲突检测 → 同一时段两场会议（双重预订，实测 10 轮 7 轮复现）；
 * ② 数据库兜底：会议室行锁（FOR UPDATE）保证 Redis 锁失效时并发仍被串行化；
 * ③ 免锁边界：只改 title/remark 不动占用时段，不应被房间锁挡住（无谓地把改信息也串行化是过度设计）。
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
class MeetingReserveLockTest {

    @Autowired
    private MeetingReserveService reserveService;
    @Autowired
    private MeetingMapper meetingMapper;
    @Autowired
    private MeetingRoomMapper meetingRoomMapper;
    @Autowired
    private RedissonClient redisson;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final Long ROOM_ID = 1L;
    private static final Long OWNER_ID = 1L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    private static final LocalDateTime T14 = DAY.atTime(14, 0);
    private static final LocalDateTime T15 = DAY.atTime(15, 0);
    private static final LocalDateTime T16 = DAY.atTime(16, 0);
    private static final LocalDateTime T17 = DAY.atTime(17, 0);

    private Long meetingId;

    @AfterEach
    void cleanUp() {
        if (meetingId != null) {
            jdbcTemplate.update("DELETE FROM meeting_status_log WHERE meeting_id = ?", meetingId);
            jdbcTemplate.update("DELETE FROM meeting WHERE id = ?", meetingId);
            meetingId = null;
        }
    }

    /** ① 改期必须与预约锁互斥：锁被占用时抢锁失败（10007），会议不得被改动 */
    @Test
    void updateScheduleShouldRespectReserveLock() throws InterruptedException {
        int version = insertReservedMeeting();

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdRoomLock(held, release);
        assertTrue(held.await(5, TimeUnit.SECONDS), "测试前置失败：持锁线程未能在 5 秒内拿到锁");

        try {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> reserveService.update(meetingId, updateDTO(version, T16, T17), OWNER_ID),
                    "预约锁被占用时，改期必须抢锁失败，而不是绕开锁继续写入");
            assertEquals(ErrorCode.SYSTEM_BUSY.getCode(), e.getCode());
        } finally {
            release.countDown();
            holder.join(5000);
        }

        Meeting after = meetingMapper.selectById(meetingId);
        assertEquals(T14, after.getStartTime(), "被锁拦下的改期不得写入新时间");
        assertEquals(version, after.getVersion().intValue(), "被锁拦下的改期不得推进乐观锁版本");
    }

    /** ② 会议室行锁串行化：一个事务持锁期间，另一个事务进不了临界区（Redis 锁失效时的兜底） */
    @Test
    void roomRowLockShouldSerializeConcurrentTransactions() throws InterruptedException {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = new Thread(() -> transactionTemplate.executeWithoutResult(tx -> {
            meetingRoomMapper.selectIdForUpdate(ROOM_ID);
            firstLocked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        first.start();
        assertTrue(firstLocked.await(5, TimeUnit.SECONDS), "测试前置失败：第一个事务未能锁住会议室行");

        CountDownLatch secondDone = new CountDownLatch(1);
        try {
            Thread second = new Thread(() -> {
                transactionTemplate.executeWithoutResult(tx -> meetingRoomMapper.selectIdForUpdate(ROOM_ID));
                secondDone.countDown();
            });
            second.start();

            assertFalse(secondDone.await(500, TimeUnit.MILLISECONDS),
                    "第一个事务持锁期间，第二个事务必须被挡在临界区外");
        } finally {
            release.countDown();
        }
        assertTrue(secondDone.await(5, TimeUnit.SECONDS), "第一个事务释放后，第二个事务应能获得行锁");
    }

    /** ③ 只改标题/备注不抢房间锁：时段被别的预约占着，改信息也应照常成功 */
    @Test
    void updateWithoutScheduleChangeShouldNotNeedRoomLock() throws InterruptedException {
        int version = insertReservedMeeting();
        MeetingUpdateDTO dto = updateDTO(version, T14, T15); // 时间/会议室不变 → 不触发改期分支
        dto.setTitle("只改标题");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdRoomLock(held, release);
        assertTrue(held.await(5, TimeUnit.SECONDS), "测试前置失败：持锁线程未能在 5 秒内拿到锁");

        try {
            reserveService.update(meetingId, dto, OWNER_ID);
        } finally {
            release.countDown();
            holder.join(5000);
        }

        Meeting after = meetingMapper.selectById(meetingId);
        assertEquals("只改标题", after.getTitle());
        assertEquals(version + 1, after.getVersion().intValue(), "改信息应正常推进乐观锁版本");
    }

    /**
     * 另起线程持有该会议室当天的预约锁（key 契约见 MeetingLockHelper）。
     * 必须换线程——Redisson 锁同线程可重入，同线程持锁再抢不会失败。
     */
    private Thread holdRoomLock(CountDownLatch held, CountDownLatch release) {
        Thread holder = new Thread(() -> {
            RLock lock = redisson.getLock("lock:meeting:" + ROOM_ID + ":" + DAY);
            lock.lock(15, TimeUnit.SECONDS);
            held.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
        holder.start();
        return holder;
    }

    /** 造一场 room1 T14-T15 的已预约会议（记入 meetingId 供清理），返回其乐观锁版本号 */
    private int insertReservedMeeting() {
        Meeting meeting = new Meeting();
        meeting.setTitle("改期锁测试");
        meeting.setRoomId(ROOM_ID);
        meeting.setOrganiserId(OWNER_ID);
        meeting.setStartTime(T14);
        meeting.setEndTime(T15);
        meeting.setStatus(20);
        meetingMapper.insert(meeting);
        meetingId = meeting.getId();
        return meetingMapper.selectById(meetingId).getVersion();
    }

    private MeetingUpdateDTO updateDTO(int version, LocalDateTime start, LocalDateTime end) {
        MeetingUpdateDTO dto = new MeetingUpdateDTO();
        dto.setTitle("改期锁测试");
        dto.setRoomId(ROOM_ID);
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setVersion(version);
        return dto;
    }
}

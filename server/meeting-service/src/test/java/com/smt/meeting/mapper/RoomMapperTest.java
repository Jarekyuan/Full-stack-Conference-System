package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingRoom;
import com.smt.meeting.entity.RoomGroup;
import com.smt.meeting.entity.RoomStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议室数据层联调测试（依赖本地 MySQL；禁用 Nacos 注册）
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
class RoomMapperTest {

    @Autowired
    private RoomGroupMapper roomGroupMapper;
    @Autowired
    private MeetingRoomMapper meetingRoomMapper;
    @Autowired
    private RoomStrategyMapper roomStrategyMapper;

    @Test
    void selectAllGroupsShouldReturn3() {
        List<RoomGroup> groups = roomGroupMapper.selectAll();
        assertEquals(3, groups.size());
        assertEquals("3F 东区", groups.get(0).getName());
    }

    @Test
    void selectAllRoomsShouldContainSeedRoomsWithJsonParsed() {
        List<MeetingRoom> rooms = meetingRoomMapper.selectAll();
        // 不锁死总数：管理员本来就能在界面上新增会议室（2026-10-17 用户验证时就加了一间“大明宫”）。
        // 断言“种子会议室都在 + 至少 6 间”，既守住种子数据，又不把正常业务写操作当成测试失败。
        assertTrue(rooms.size() >= 6, "至少应有 6 间种子会议室");
        List<String> names = rooms.stream().map(MeetingRoom::getName).toList();
        assertTrue(names.containsAll(List.of("青竹厅", "白桦厅", "梧桐厅", "银杏厅", "红枫厅", "云杉厅")),
                "6 间种子会议室应都在，实际=" + names);

        MeetingRoom wutong = rooms.stream().filter(r -> r.getId() == 3L).findFirst().orElseThrow();
        assertEquals("梧桐厅", wutong.getName());
        // JSON 列经 JsonTypeHandler 解析为 List
        assertTrue(wutong.getDeviceTags().contains("视频会议"), "deviceTags 应包含'视频会议'");
        assertNotNull(wutong.getImages());
        assertTrue(wutong.getImages().isEmpty(), "images 为 NULL 时应映射为空列表");
    }

    @Test
    void selectByIdShouldReturnRoom() {
        MeetingRoom room = meetingRoomMapper.selectById(1L);
        assertNotNull(room);
        assertEquals("青竹厅", room.getName());
        assertFalse(room.getDeviceTags().isEmpty());
    }

    @Test
    void selectStrategyShouldReturnApprovalSettings() {
        RoomStrategy strategy = roomStrategyMapper.selectByRoomId(3L);
        assertNotNull(strategy);
        assertEquals(1, strategy.getNeedApproval(), "梧桐厅应需审批");
        assertEquals(15, strategy.getBufferMinutes(), "梧桐厅应含 15 分钟锁定缓冲");
        assertEquals(1, strategy.getAllowRecurring());
    }
}

package com.robot.fleet.controller;

import com.robot.fleet.dto.BatteryDto;
import com.robot.fleet.dto.PoseDto;
import com.robot.fleet.dto.RobotStatusDto;
import com.robot.fleet.service.RobotQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/robots")
@RequiredArgsConstructor
public class RobotController {

    private final RobotQueryService queryService;

    @GetMapping
    public List<RobotStatusDto> getAllRobots() {
        return queryService.getAllRobots();
    }

    @GetMapping("/{robotId}/pose/latest")
    public ResponseEntity<PoseDto> getLatestPose(@PathVariable String robotId) {
        PoseDto pose = queryService.getLatestPose(robotId);
        return pose != null ? ResponseEntity.ok(pose) : ResponseEntity.notFound().build();
    }

    @GetMapping("/{robotId}/pose/history")
    public List<PoseDto> getPoseHistory(
            @PathVariable String robotId,
            @RequestParam(defaultValue = "100") int limit) {
        return queryService.getPoseHistory(robotId, limit);
    }

    @GetMapping("/{robotId}/battery/latest")
    public ResponseEntity<BatteryDto> getLatestBattery(@PathVariable String robotId) {
        BatteryDto battery = queryService.getLatestBattery(robotId);
        return battery != null ? ResponseEntity.ok(battery) : ResponseEntity.notFound().build();
    }

    @GetMapping("/{robotId}/battery/history")
    public List<BatteryDto> getBatteryHistory(
            @PathVariable String robotId,
            @RequestParam(defaultValue = "100") int limit) {
        return queryService.getBatteryHistory(robotId, limit);
    }
}

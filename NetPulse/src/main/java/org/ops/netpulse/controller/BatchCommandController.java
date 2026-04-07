package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.BatchCommandService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/batch/command")
@RequiredArgsConstructor
@CrossOrigin
public class BatchCommandController {

    private final BatchCommandService batchCommandService;
    private final AuditService auditService;

    @PostMapping
    public List<BatchCommandService.CommandResult> execute(@RequestBody BatchCommandRequest request) {
        List<Long> ids = request.getDeviceIds() != null ? request.getDeviceIds() : List.of();
        String cmd = request.getCommand() != null ? request.getCommand() : "";
        List<BatchCommandService.CommandResult> results = batchCommandService.execute(ids, cmd);
        auditService.log("BATCH_COMMAND", "batch_command", null, "devices=" + ids.size() + ",command=" + (cmd.length() > 50 ? cmd.substring(0, 50) + "..." : cmd));
        return results;
    }

    public static class BatchCommandRequest {
        private List<Long> deviceIds;
        private String command;

        public List<Long> getDeviceIds() { return deviceIds; }
        public void setDeviceIds(List<Long> deviceIds) { this.deviceIds = deviceIds; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
    }
}

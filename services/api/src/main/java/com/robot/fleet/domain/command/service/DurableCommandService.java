package com.robot.fleet.domain.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.robot.fleet.domain.command.dto.*;
import com.robot.fleet.domain.command.repository.CommandOutboxRepository;
import com.robot.fleet.domain.ack.service.AckQueryService;
import com.robot.fleet.domain.realtime.RealtimeState;
import com.robot.fleet.domain.simulation.service.SimulationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class DurableCommandService {
    private final CommandOutboxRepository repository;
    private final SimulationService simulations;
    private final RealtimeState realtime;
    private final AckQueryService acks;
    private final ObjectMapper mapper;
    private final ObjectMapper canonical = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final TransactionTemplate transactions;
    private final String fleet;
    private final boolean enabled;
    public DurableCommandService(CommandOutboxRepository repository, SimulationService simulations, RealtimeState realtime,
        AckQueryService acks, ObjectMapper mapper, PlatformTransactionManager manager,
        @Value("${fleet.id:default}") String fleet, @Value("${command.durable-enabled:false}") boolean enabled) {
        this.repository=repository; this.simulations=simulations; this.realtime=realtime; this.acks=acks;
        this.mapper=mapper; this.transactions=new TransactionTemplate(manager); this.fleet=fleet; this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    public String accept(CommandRequestDto request) {
        if (request.getRobotId()==null || !request.getRobotId().matches("[A-Za-z0-9_-]{1,64}") ||
            !Set.of("stop","move_to").contains(Objects.toString(request.getCommand(),"")) || request.getData()==null)
            throw new IllegalArgumentException("잘못된 명령");
        if (request.getCommand().equals("move_to") && (!(request.getData() instanceof MoveToCommandDataDto point) || !Double.isFinite(point.x()) || !Double.isFinite(point.y())))
            throw new IllegalArgumentException("유한한 이동 좌표가 필요합니다");
        if (request.getCommand().equals("stop") && !(request.getData() instanceof StopCommandDataDto)) throw new IllegalArgumentException("잘못된 정지 데이터");
        UUID key = request.getIdempotencyKey()==null ? null : UUID.fromString(request.getIdempotencyKey());
        final String hash;
        try {
            hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(
                List.of(request.getRobotId(),request.getCommand(),request.getData(),Objects.toString(request.getIssuedBy(),"")))));
        } catch (Exception e) { throw new IllegalArgumentException("명령 직렬화 실패", e); }
        // The unique key resolves races after the transaction rolls back, including sequence allocation.
        try {
            return transactions.execute(status -> {
                if (key!=null) {
                    var previous=repository.byKey(fleet,key);
                    if (previous.isPresent()) return same(previous.get(),hash);
                }
                realtime.requireAvailable();
                var context=simulations.commandContext(request.getRobotId());
                long sequence=repository.nextSequence(fleet,request.getRobotId());
                var now=repository.now();
                var expiry=now.plusSeconds(request.getCommand().equals("stop") ? 5 : 10);
                UUID id=UUID.randomUUID();
                var payload=new LinkedHashMap<String,Object>(context);
                payload.put("cmd_id",id.toString()); payload.put("fleet_id",fleet); payload.put("robot_id",request.getRobotId());
                payload.put("command",request.getCommand()); payload.put("data",request.getData()); payload.put("issued_by",request.getIssuedBy());
                payload.put("timestamp",now.toString()); payload.put("expires_at",expiry.toString()); payload.put("command_sequence",sequence);
                try { repository.save(id,fleet,request.getRobotId(),key,hash,mapper.writeValueAsString(payload),sequence,expiry); }
                catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("명령 직렬화 실패",e); }
                return id.toString();
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            if (key==null) throw e;
            return repository.byKey(fleet,key).map(entry -> same(entry,hash)).orElseThrow(() -> e);
        }
    }
    private String same(CommandOutboxRepository.Entry entry, String hash) {
        if (!entry.requestHash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT,"같은 키의 명령 내용이 다릅니다");
        return entry.cmdId().toString();
    }
    public Optional<CommandStatusDto> find(UUID id) { return repository.byId(fleet,id).map(this::view); }
    public Optional<CommandStatusDto> byKey(UUID key) { return repository.byKey(fleet,key).map(this::view); }
    private CommandStatusDto view(CommandOutboxRepository.Entry entry) {
        var ack=acks.find(entry.robotId(),entry.cmdId()).orElse(null);
        String state=entry.unknown() ? "OUTCOME_UNKNOWN" : entry.receivedAt()!=null ? "RECEIVED" : "PENDING";
        if (entry.receivedAt()==null && repository.now().isAfter(entry.expiresAt())) state=entry.attempts()==0 ? "EXPIRED" : "OUTCOME_UNKNOWN";
        if (ack!=null && Set.of("done","failed").contains(ack.status())) state="COMPLETED";
        return new CommandStatusDto(entry.cmdId().toString(),entry.robotId(),state,entry.expiresAt(),ack);
    }
    public record Receipt(UUID cmdId, String robotId, boolean outcomeUnknown) { }
    public void received(List<Receipt> entries) {
        if (entries==null || entries.size()>100 || entries.stream().anyMatch(e -> e==null || e.cmdId()==null || e.robotId()==null)) throw new IllegalArgumentException("잘못된 수신 확인");
        transactions.executeWithoutResult(s -> entries.forEach(e -> repository.received(fleet,e.cmdId(),e.robotId(),e.outcomeUnknown())));
    }
    public List<UUID> confirmed(List<UUID> events) {
        if (events==null || events.size()>100 || events.contains(null)) throw new IllegalArgumentException("잘못된 Ack 조회");
        return events.stream().filter(e -> repository.receipt(fleet,e)).toList();
    }
}

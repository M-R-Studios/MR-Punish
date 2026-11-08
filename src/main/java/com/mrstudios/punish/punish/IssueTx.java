package com.mrstudios.punish.punish;

import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.TimeSpan;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.punish.Punishment.WeightSource;
import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.rank.Authority.Standing;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.Database;
import com.mrstudios.punish.storage.PlayerRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.PunishmentRepo.Subject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Issuing one punishment, inside one database transaction (DESIGN.md 7.10 step 4). Everything is
 * re-checked here against what the database says now: the target's weight, authority, escalation
 * and the slot. Only if all of it still holds is anything written, and then all of it is written
 * together: the punishment, the replaced rows, the staff action and (offline warnings) the delivery.
 */
final class IssueTx {

    /** Settings read once when the action started, so a reload can't change them halfway. */
    record Context(TimeSpan historyPeriod, TimeSpan warningActiveFor, String server, long associationSince) {

        Context(TimeSpan historyPeriod, TimeSpan warningActiveFor, String server) {
            this(historyPeriod, warningActiveFor, server, Long.MAX_VALUE);
        }
    }

    private final Database database;
    private final PunishmentRepo punishments;
    private final ActionRepo actions;
    private final PlayerRepo players;

    IssueTx(Database database) {
        this.database = database;
        this.punishments = new PunishmentRepo(database);
        this.actions = new ActionRepo(database);
        this.players = new PlayerRepo(database);
    }

    /**
     * @param fromLuckPerms the offline target's standing from LuckPerms, if it was loaded
     */
    IssueResult run(Connection c, Requests.Issue request, Optional<Standing> fromLuckPerms, Context context, long now)
            throws SQLException {
        Requests.Target target = request.target();
        PunishmentType type = request.type();
        punishments.lock(c, target.uuid(), type.isIp() ? target.ipHash() : null);

        // The target's weight as it is now (DESIGN.md 6.4).
        Standing standing;
        WeightSource source;
        if (target.live() != null) {
            standing = target.live();
            source = WeightSource.LIVE;
        } else {
            Optional<PlayerRepo.PlayerRecord> row = players.byUuid(c, target.uuid());
            if (row.isEmpty() && fromLuckPerms.isEmpty()) {
                return new IssueResult.UnknownTarget();
            }
            int cachedWeight = row.map(PlayerRepo.PlayerRecord::rankWeight).orElse(0);
            boolean cachedExempt = row.map(PlayerRepo.PlayerRecord::exempt).orElse(false);
            if (fromLuckPerms.isPresent()) {
                // The higher of the two: a promotion LuckPerms knows about protects the target at once,
                // and a node only Bukkit knows (op) isn't lost because LuckPerms doesn't see it.
                Standing lp = fromLuckPerms.get();
                standing = new Standing(target.uuid(), target.name(), Math.max(lp.weight(), cachedWeight),
                        lp.exempt() || cachedExempt);
                source = WeightSource.LUCKPERMS;
            } else {
                standing = new Standing(target.uuid(), target.name(), cachedWeight, cachedExempt);
                source = WeightSource.CACHED;
            }
        }

        Standing actor = request.actor().standing();
        List<Standing> affected = new ArrayList<>();
        affected.add(standing);
        affected.addAll(target.affected());
        if (type.isIp()) {
            // The associated accounts as the database says now (DESIGN.md 7.10 step 4.2): someone promoted
            // or newly seen on the IP since the command started is still protected. The higher weight wins.
            java.util.Map<java.util.UUID, Standing> byUuid = new java.util.LinkedHashMap<>();
            for (Standing s : affected) {
                byUuid.put(s.uuid(), s);
            }
            for (com.mrstudios.punish.storage.IpRepo.Seen seen : new com.mrstudios.punish.storage.IpRepo(database)
                    .associated(c, target.ipHash(), context.associationSince())) {
                Standing known = byUuid.get(seen.uuid());
                Standing cached = new Standing(seen.uuid(), seen.name(), seen.weight(), seen.exempt());
                byUuid.put(seen.uuid(), known == null ? cached : new Standing(known.uuid(), known.name(),
                        Math.max(known.weight(), seen.weight()), known.exempt() || seen.exempt()));
            }
            affected = new ArrayList<>(byUuid.values());
        }
        Authority.Action action = type.isIp() ? Authority.Action.IP_PUNISH : Authority.Action.PUNISH;
        Authority.Verdict verdict = Authority.check(actor, affected, action);
        if (verdict != Authority.Verdict.OK) {
            actions.insert(c, new ActionRepo.Action(now, context.server(), actor.uuid(), actor.name(), "REFUSED",
                    target.uuid(), target.name(), null, type + " " + request.reason().key() + " refused: " + verdict));
            return new IssueResult.Refused(verdict, Authority.refusedCount(actor, affected));
        }

        Subject subject = Subject.of(type, target.uuid(), target.ipHash());
        List<EscalationCalculator.Prior> priors = punishments.priors(c, type, request.reason().key(), subject);
        List<ReplacementRules.Active> active = new ArrayList<>();
        if (type.hasDuration()) {
            for (Punishment p : punishments.active(c, type, subject, now)) {
                active.add(new ReplacementRules.Active(p.id(), p.expiresAt()));
            }
        }
        Preview preview = EscalationCalculator.preview(request.reason(), context.historyPeriod(), priors, active, now);
        if (request.expected() != null && !request.expected().sameDecision(preview)) {
            return new IssueResult.Changed(preview);
        }
        if (preview.decision().blocked()) {
            Punishment blocking = punishments.byId(c, preview.decision().blockedBy().id()).orElseThrow();
            return new IssueResult.WouldShorten(blocking);
        }

        Long expiresAt;
        Long durationMs;
        if (type.hasDuration()) {
            durationMs = preview.duration().permanent() ? null : preview.duration().millis();
            expiresAt = preview.endsAt();
        } else {
            durationMs = null;
            TimeSpan activeFor = context.warningActiveFor();
            expiresAt = activeFor.permanent() ? null : now + activeFor.millis();
        }
        // Console has no rank: its punishments can be removed by anyone allowed to remove (weight 0).
        int issuerWeight = actor.isConsole() ? 0 : actor.weight();
        Punishment draft = new Punishment(0, type, target.uuid(), target.name(), type.isIp() ? target.ipHash() : null,
                request.reason().key(), request.reason().display(), request.note(), preview.offence(), preview.step(),
                durationMs, now, expiresAt, actor.uuid(), actor.name(), issuerWeight, standing.weight(), source,
                context.server(), request.silent(), request.origin(), StoredStatus.ACTIVE, null, null, null, null, null,
                null);
        long id = punishments.insert(c, draft);
        List<Long> replaced = preview.decision().replaces();
        punishments.markReplaced(c, replaced, id, now);

        StringBuilder details = new StringBuilder(type.name()).append(' ').append(request.reason().key()).append(' ')
                .append(preview.duration() == null ? "-" : preview.duration().permanent() ? "permanent"
                        : DurationParser.format(preview.duration().millis()))
                .append(" offence=").append(preview.offence());
        if (request.silent()) {
            details.append(" silent");
        }
        if (!replaced.isEmpty()) {
            details.append(" replaced=").append(replaced.stream().map(i -> "#" + i).toList());
        }
        if (type.isIp()) {
            details.append(" accounts=").append(affected.size());
        }
        actions.insert(c, new ActionRepo.Action(now, context.server(), actor.uuid(), actor.name(), "PUNISH",
                target.uuid(), target.name(), id, details.toString()));
        if (type == PunishmentType.WARN && !target.online()) {
            actions.deliver(c, target.uuid(), "WARN", id, now);
        }
        return new IssueResult.Committed(punishments.byId(c, id).orElseThrow(), replaced, preview);
    }
}

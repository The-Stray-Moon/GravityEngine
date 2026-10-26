// Runs on the real server thread through AutoJS; no test mod or JVM substitute.
// The actor is a temporary ServerPlayer with a native listener and an in-memory
// Netty transport. This exercises transformed handling, not a multiplayer wire.
(function () {
    function C(name) { return Java.loadClass(name); }
    var ServerPlayer = C('net.minecraft.server.level.ServerPlayer');
    var Profile = C('com.mojang.authlib.GameProfile');
    var UUID = C('java.util.UUID');
    var ClientInformation = C('net.minecraft.server.level.ClientInformation');
    var Connection = C('net.minecraft.network.Connection');
    var EmbeddedChannel = C('io.netty.channel.embedded.EmbeddedChannel');
    var NetworkRegistry = C('net.neoforged.neoforge.network.registration.NetworkRegistry');
    var PacketFlow = C('net.minecraft.network.protocol.PacketFlow');
    var Listener = C('net.minecraft.server.network.ServerGamePacketListenerImpl');
    var Cookie = C('net.minecraft.server.network.CommonListenerCookie');
    var Pos = C('net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Pos');
    var Rot = C('net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Rot');
    var Ack = C('net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket');
    var Receiver = C('cc.sighs.gravityengine.network.ServerPlayerMovementReceiver');
    var Payload = C('cc.sighs.gravityengine.network.ServerboundPlayerMovePayload');
    var Access = C('cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess');
    var Coordinator = C('cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator');
    var State = C('cc.sighs.gravityengine.gravity.GravityState');
    var V = C('cc.sighs.gravityengine.api.math.Vec3d');
    var Vec3 = C('net.minecraft.world.phys.Vec3');
    var BlockPos = C('net.minecraft.core.BlockPos');
    var Blocks = C('net.minecraft.world.level.block.Blocks');
    var ModList = C('net.neoforged.fml.ModList');
    var System = C('java.lang.System');
    var server = AutoJS.server(), level = AutoJS.level();
    var checks = [], actors = [], channels = [], saved = [];
    var result = {runId: __GE_RUN.id, scenario: 'packet-fall', status: 'FAIL',
        checks: checks, observations: {}, cleanup: 'NOT RUN',
        scope: 'live transformed server; temporary player; no socket client'};
    function check(name, condition, observed) {
        checks.push({name: name, status: condition ? 'PASS' : 'FAIL', observed: observed});
        if (!condition) throw new Error(name + ': ' + JSON.stringify(observed));
    }
    function near(a, b) { return Math.abs(a - b) < 0.00001; }
    function component(p) { return Access.cast(p).gravityengine$gravityComponent(); }
    function send(p, packet) {
        Receiver.receive(p, new Payload(level.dimension().location(), component(p).state().applicationEpoch(), packet));
        check('handler scope cleaned', Receiver.current(p) == null, String(Receiver.current(p)));
        check('MOVE result released', component(p).operationState().currentMoveResult() == null,
            String(component(p).operationState().currentMoveResult()));
    }
    function actor(name, x, y, z) {
        var profile = new Profile(UUID.randomUUID(), name);
        var p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        actors.push(p);
        var wire = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(wire);
        channels.push(channel);
        // NeoForge's version-matched fixture helper advertises registered channels.
        // No handshake/serialization or client acceptance is claimed by this run.
        NetworkRegistry.configureMockConnection(wire);
        p.connection = new Listener(server, wire, p,
            Cookie.createInitial(profile, false));
        p.setPos(x, y, z);
        level.addNewPlayer(p);
        p.connection.resetPosition();
        return p;
    }
    function reset(p, x, y, z) {
        p.setPos(x, y, z); p.setDeltaMovement(Vec3.ZERO); p.setOnGround(false);
        p.fallDistance = 0; p.connection.resetPosition();
    }
    try {
        check('server thread', server != null && server.isSameThread(), String(server));
        result.versions = {minecraft: String(server.getServerVersion()), java: String(System.getProperty('java.version'))};
        ['gravityengine', 'neoforge', 'autojs', 'rhino', 'sable', 'create', 'aeronautics'].forEach(function (id) {
            var mod = ModList.get().getModContainerById(id);
            result.versions[id] = mod.isPresent() ? String(mod.get().getModInfo().getVersion()) : null;
        });
        check('version matched runtime', result.versions.minecraft === '1.21.1' && result.versions.neoforge === '21.1.256', result.versions);
        check('optional profile', (result.versions.sable != null) === (__GE_RUN.profile !== 'none'), result.versions.sable);
        // Save and restore every edited block; never touch an online player.
        var originX = 40, originY = 240, originZ = 40;
        for (var x = 37; x <= 48; x++) for (var y = 238; y <= 244; y++) for (var z = 38; z <= 42; z++) {
            var pos = new BlockPos(x, y, z);
            level.getChunk(x >> 4, z >> 4);
            if (level.getBlockEntity(pos) != null) throw new Error('fixture area contains a block entity');
            saved.push({pos: pos, state: level.getBlockState(pos)});
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
        }
        var p = actor('GEAutoJSPacket', originX, originY, originZ);
        Coordinator.applyDirectAssignment(p, new State(new V(1, 0, 0), 0.08));
        // Networked bodies commit at the real connection-tick handoff, not at
        // assignment time and not by bypassing the geometry authority.
        p.connection.tick();
        var up = component(p).operationState().installedCollisionUp();
        check('sideways collider installed', up != null && near(up.x(), -1), String(up));
        reset(p, originX, originY, originZ);
        var tick = level.getGameTime();
        send(p, new Pos(42, originY, originZ, false));
        check('non-Y packet fall consumes closed MOVE', near(p.fallDistance, 2) && near(p.getY(), originY),
            {fallDistance: p.fallDistance, x: p.getX(), y: p.getY()});
        send(p, new Pos(43, originY, originZ, false));
        check('same-tick packet isolation', level.getGameTime() === tick && near(p.fallDistance, 3),
            {tick: Number(tick), fallDistance: p.fallDistance});
        send(p, new Rot(20, 10, false));
        check('rotation packet cannot replay prior fall', near(p.fallDistance, 3), p.fallDistance);
        send(p, new Pos(42, originY, originZ, false));
        check('environmental upward reset', near(p.fallDistance, 0), p.fallDistance);

        // A real world wall makes Ps != the accepted client Pc. Old endpoint facts
        // must not clear or accumulate fall at the new accepted endpoint.
        for (var wy = 239; wy <= 243; wy++) for (var wz = 39; wz <= 41; wz++)
            level.setBlock(new BlockPos(44, wy, wz), Blocks.STONE.defaultBlockState(), 18);
        p.fallDistance = 7;
        send(p, new Pos(45, originY, originZ, false));
        check('client endpoint retained without old fall facts', near(p.getX(), 45) && near(p.fallDistance, 7),
            {x: p.getX(), fallDistance: p.fallDistance});

        var nativePlayer = actor('GEAutoJSNative', 40, 240, 40);
        nativePlayer.noPhysics = true;
        send(nativePlayer, new Pos(41, 240, 40, true));
        check('noPhysics native ground', nativePlayer.onGround() && near(nativePlayer.getX(), 41),
            {ground: nativePlayer.onGround(), x: nativePlayer.getX()});

        // Raw native packets still reject excessive speed and preserve native ACK.
        reset(nativePlayer, 40, 240, 40);
        nativePlayer.noPhysics = false;
        nativePlayer.connection.handleMovePlayer(new Pos(1000, 240, 40, false));
        check('raw native speed rejection', near(nativePlayer.getX(), 40), nativePlayer.getX());
        nativePlayer.connection.handleMovePlayer(new Pos(41, 240, 40, false));
        check('pending teleport blocks movement', near(nativePlayer.getX(), 40), nativePlayer.getX());
        // This new listener has issued exactly its first teleport (native ID 1).
        nativePlayer.connection.handleAcceptTeleportPacket(new Ack(1));
        nativePlayer.connection.handleMovePlayer(new Pos(41, 240, 40, false));
        check('native teleport ACK restores movement', near(nativePlayer.getX(), 41), nativePlayer.getX());
        result.status = 'PASS';
    } catch (failure) {
        result.error = String(failure);
        result.stack = String(failure.stack);
        result.status = 'FAIL';
    } finally {
        var cleanupErrors = [];
        actors.forEach(function (p) { try { p.discard(); } catch (e) { cleanupErrors.push(String(e)); } });
        channels.forEach(function (c) { try { c.finishAndReleaseAll(); } catch (e) { cleanupErrors.push(String(e)); } });
        saved.forEach(function (b) { try { level.setBlock(b.pos, b.state, 18); } catch (e) { cleanupErrors.push(String(e)); } });
        result.cleanup = cleanupErrors.length === 0 ? 'PASS' : 'FAIL';
        if (cleanupErrors.length) { result.cleanupErrors = cleanupErrors; result.status = 'FAIL'; }
    }
    return JSON.stringify(result);
})();

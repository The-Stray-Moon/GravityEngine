// Real server-thread scenario. Unspawned cows own temporary native attachments;
// no world blocks, existing entities or files are mutated by this evaluation.
(function () {
    function C(name) { return Java.loadClass(name); }
    var Tag = C('net.minecraft.nbt.CompoundTag');
    var EntityType = C('net.minecraft.world.entity.EntityType');
    var Access = C('cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess');
    var Serializer = C('cc.sighs.gravityengine.gravity.persistence.GravityPersistenceSerializer').INSTANCE;
    var Codec = C('cc.sighs.gravityengine.gravity.persistence.GravityPersistenceCodec');
    var Attachments = C('cc.sighs.gravityengine.GravityEngineAttachments');
    var Coverage = C('cc.sighs.gravityengine.api.field.FieldCoverage');
    var ModList = C('net.neoforged.fml.ModList');
    var server = AutoJS.server(), level = AutoJS.level(), actors = [], checks = [];
    var result = {runId: __GE_RUN.id, scenario: 'current-format', status: 'FAIL',
        checks: checks, cleanup: 'NOT RUN', inputs: {record: 'structural', durableRevision: 10},
        scope: 'live server attachment serialization and assignment lifecycle; no client'};
    function check(name, condition, value) {
        checks.push({name: name, status: condition ? 'PASS' : 'FAIL', observed: String(value)});
        if (!condition) throw new Error(name + ': ' + value);
    }
    function actor() {
        var e = EntityType.COW.create(level);
        actors.push(e);
        return e;
    }
    function state(e) { return Access.cast(e).gravityengine$gravityComponent().state(); }
    function record() {
        var t = new Tag();
        t.putDouble('DownX', 0); t.putDouble('DownY', -1); t.putDouble('DownZ', 0);
        t.putDouble('Strength', 1); t.putLong('AssignmentRev', 10);
        t.putInt('Authority', C('cc.sighs.gravityengine.gravity.model.GravityAuthorityMode').FIELD.networkId());
        t.putBoolean('FieldContinuity', true);
        return t;
    }
    try {
        check('owning server thread', server.isSameThread(), server.isSameThread());
        result.versions = {minecraft: String(server.getServerVersion())};
        ['gravityengine', 'neoforge', 'autojs'].forEach(function (id) {
            result.versions[id] = String(ModList.get().getModContainerById(id).get().getModInfo().getVersion());
        });
        var e = actor(), original = record(), slot = Serializer.read(e, original, level.registryAccess());
        e.setData(Attachments.GRAVITY_PERSISTENCE, slot);
        var s = state(e);
        check('durable revision restored independently', s.assignmentRevision() == 10 && s.assignmentSyncRevision() == 0,
            s.assignmentRevision() + '/' + s.assignmentSyncRevision());
        check('restored evidence unknown', String(s.fieldPresence()) === 'UNKNOWN', s.fieldPresence());
        check('current record roundtrip', original.equals(Serializer.write(slot, level.registryAccess())), slot.decodeStatus());
        var saved = e.saveWithoutId(new Tag()), reloaded = actor();
        reloaded.load(saved);
        check('native attachment save/load', state(reloaded).assignmentRevision() == 10 && state(reloaded).assignmentSyncRevision() == 0,
            state(reloaded).assignmentRevision() + '/' + state(reloaded).assignmentSyncRevision());
        s.commitFieldEvaluation(s.assignedState(), true, Coverage.INCOMPLETE);
        check('partial evidence retains seed', s.assignmentRevision() == 10 && s.assignmentSyncRevision() == 0,
            s.assignmentRevision() + '/' + s.assignmentSyncRevision());
        s.commitFieldEvaluation(s.assignedState(), true, Coverage.COMPLETE);
        check('evidence-only resolution advances live sequence', s.assignmentRevision() == 10 && s.assignmentSyncRevision() == 1,
            s.assignmentRevision() + '/' + s.assignmentSyncRevision());
        s.commitFieldEvaluation(s.assignedState(), true, Coverage.COMPLETE);
        check('identical evidence idempotent', s.assignmentSyncRevision() == 1, s.assignmentSyncRevision());
        var Plan = C('cc.sighs.gravityengine.gravity.model.GravityApplicationPlan');
        var Mode = C('cc.sighs.gravityengine.gravity.model.GravityAccelerationMode');
        var Application = C('cc.sighs.gravityengine.gravity.model.CommittedGravityApplication');
        var Suppression = C('cc.sighs.gravityengine.gravity.model.GravitySuppressionReason');
        var Contexts = C('cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContexts');
        s.commitAuthoritativeApplication(new Application(s.assignedState(), Suppression.NONE, Plan.character(Mode.FIELD)));
        var remote = Contexts.captureRemoteResult(s);
        var local = Contexts.capture(s, C('cc.sighs.gravityengine.gravity.field.GravityFieldRuntime').get(level));
        check('remote FIELD result has no registry dependency', !remote.usesFieldRegistry(), remote);
        check('server FIELD query retains registry dependency', local.usesFieldRegistry(), local);
        check('query and committed result cannot alias', !remote.samePhysicalContext(local)
            && !local.samePhysicalContext(remote), remote);
        var body = C('cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload').capture(e, null);
        check('non-player publication carries installed extents', body.installedWidth() > 0 && body.installedHeight() > 0, body);
        var marker = EntityType.MARKER.create(level); actors.push(marker);
        var markerBody = C('cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload').capture(marker, null);
        check('native zero-size entity can be synchronized', markerBody.installedWidth() == 0, markerBody);
        var extra=record();extra.putInt('Unexpected',1);
        var rejected=actor(),bad=Serializer.read(rejected,extra,level.registryAccess());
        check('unknown structure rejected',String(bad.decodeStatus())==='MALFORMED',bad.decodeStatus());
        check('invalid structure not written back',Serializer.write(bad,level.registryAccess())==null,bad.snapshotForSave());
        check('invalid structure not imported',state(rejected).assignmentRevision()==0,state(rejected).assignmentRevision());
        check('gravity contains only assignment fields',original.getAllKeys().size()==7,original);
        var profile=new (C('com.mojang.authlib.GameProfile'))(C('java.util.UUID').randomUUID(),'GESeed');
        var player=new (C('net.minecraft.server.level.ServerPlayer'))(server,level,profile,C('net.minecraft.server.level.ClientInformation').createDefault());
        actors.push(player);
        var attitude=new Tag();
        ['BodyQX','BodyQY','BodyQZ','ControllerQX','ControllerQY','ControllerQZ'].forEach(function(k){attitude.putDouble(k,0);});
        attitude.putDouble('BodyQW',1);attitude.putDouble('ControllerQW',1);
        var angular=new Tag();angular.putDouble('AngularMomentumX',1);angular.putDouble('AngularMomentumY',2);angular.putDouble('AngularMomentumZ',3);attitude.put('AngularDynamics',angular);
        var attitudeSerializer=C('cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistenceSerializer').INSTANCE;
        var attitudeSlot=attitudeSerializer.read(player,attitude,level.registryAccess());
        check('attitude structural seed staged',attitudeSlot.pendingLoadedSeed().isPresent(),attitudeSlot.pendingLoadedSeed());
        check('attitude structural roundtrip',attitude.equals(attitudeSerializer.write(attitudeSlot,level.registryAccess())),attitude);
        attitude.remove('BodyQW');
        check('malformed attitude rejected',!attitudeSerializer.read(player,attitude,level.registryAccess()).pendingLoadedSeed().isPresent(),attitude);
        var malformed = record(); malformed.remove('Strength');
        var badSlot = Serializer.read(actor(), malformed, level.registryAccess());
        check('malformed diagnosed and discarded', String(badSlot.decodeStatus()) === 'MALFORMED'
            && Serializer.write(badSlot, level.registryAccess()) == null, badSlot.decodeStatus());
        var maximum = record();
        maximum.putLong('AssignmentRev', C('java.lang.Long').MAX_VALUE);
        var maxEntity = actor();
        var maxSlot = Serializer.read(maxEntity, maximum, level.registryAccess());
        check('full durable counter range accepted', String(maxSlot.decodeStatus()) === 'CURRENT', maxSlot.decodeStatus());
        state(maxEntity).commitFieldEvaluation(state(maxEntity).assignedState(), true, Coverage.COMPLETE);
        check('durable maximum permits evidence resolution', state(maxEntity).assignmentSyncRevision() == 1,
            state(maxEntity).assignmentSyncRevision());
        result.status = 'PASS';
    } catch (error) {
        result.error = String(error);
    } finally {
        var errors = [];
        actors.forEach(function (e) { try { e.discard(); } catch (error) { errors.push(String(error)); } });
        result.cleanup = errors.length ? 'FAIL' : 'PASS';
        result.cleanupErrors = errors;
    }
    return JSON.stringify(result);
})();

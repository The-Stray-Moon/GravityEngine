// Real transformed server; client transport is exercised through a native listener,
// not a socket client. Fixtures are restored even on assertion failure.
(function () {
    function C(n) { return Java.loadClass(n); }
    var server=AutoJS.server(), level=AutoJS.level(), saved=[], actors=[], channels=[], checks=[];
    var result={runId:__GE_RUN.id,scenario:'impact-damage',status:'FAIL',cleanup:'NOT RUN',checks:checks,
        inputs:{speed:1.2,formula:'max(0,6.25*s*s-3)',materials:['stone','hay_block','red_bed','slime_block']},
        observations:[],scope:'live transformed server movement and packet receiver; real client and moving providers NOT RUN'};
    var Vec=C('net.minecraft.world.phys.Vec3'), Pos=C('net.minecraft.core.BlockPos'), Blocks=C('net.minecraft.world.level.block.Blocks');
    var Move=C('net.minecraft.world.entity.MoverType'), Types=C('net.minecraft.world.entity.EntityType');
    var Attr=C('net.minecraft.world.entity.ai.attributes.Attributes');
    var Integration=C('cc.sighs.gravityengine.gravity.integration.ImpactDamageIntegration');
    var Impact=C('cc.sighs.gravityengine.gravity.integration.ImpactDamageIntegration$Impact');
    var Payload=C('cc.sighs.gravityengine.network.ServerboundPlayerMovePayload');
    var Receiver=C('cc.sighs.gravityengine.network.ServerPlayerMovementReceiver');
    var Access=C('cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess');
    var Lists=C('java.util.List');
    function check(n,v,o) { checks.push({name:n,status:v?'PASS':'FAIL',observed:o}); if(!v)throw Error(n+': '+JSON.stringify(o)); }
    function near(a,b) { return Math.abs(a-b)<0.0001; }
    function pig() {
        var p=Types.PIG.create(level); actors.push(p); p.setNoAi(true); p.setPos(40.5,240.1,40.5);
        p.getAttribute(Attr.MAX_HEALTH).setBaseValue(20);p.setHealth(20);
        level.addFreshEntity(p); return p;
    }
    function floor(block) {
        for(var x=37;x<=45;x++)for(var z=38;z<=43;z++) level.setBlock(new Pos(x,239,z),block.defaultBlockState(),18);
    }
    function landing(name,block,speed,expected,tangent,distance,crouch) {
        floor(block); var p=pig(); p.fallDistance=distance || 0;
        p.setShiftKeyDown(!!crouch);
        p.setDeltaMovement(new Vec(tangent||0,-speed,0));
        p.move(Move.SELF,p.getDeltaMovement());
        var lost=20-p.getHealth();
        result.observations.push({name:name,damage:lost,position:String(p.position()),velocity:String(p.getDeltaMovement())});
        check(name,near(lost,expected),lost); p.discard(); return p;
    }
    try {
        check('server thread',server.isSameThread(),String(server));
        result.versions={minecraft:String(server.getServerVersion())};
        ['gravityengine','neoforge','autojs','rhino'].forEach(function(id){result.versions[id]=String(C('net.neoforged.fml.ModList').get().getModContainerById(id).get().getModInfo().getVersion());});
        for(var x=37;x<=45;x++)for(var z=38;z<=43;z++)for(var y=238;y<=245;y++) {
            var pos=new Pos(x,y,z); level.getChunk(x>>4,z>>4);
            if(level.getBlockEntity(pos)!=null)throw Error('fixture contains block entity');
            saved.push({pos:pos,state:level.getBlockState(pos)}); level.setBlock(pos,Blocks.AIR.defaultBlockState(),18);
        }
        landing('stone fractional damage',Blocks.STONE,1,3.25,0,100);
        landing('zero fallDistance still damages',Blocks.STONE,1.2,6);
        landing('below safe speed',Blocks.STONE,0.5,0,0,100);
        landing('tangent speed excluded',Blocks.STONE,1.2,6,2);
        landing('hay multiplier after subtraction',Blocks.HAY_BLOCK,1.2,1.2);
        landing('bed halves equivalent distance before subtraction',Blocks.RED_BED,1.2,1.5);
        var slime=landing('slime immunity',Blocks.SLIME_BLOCK,1.2,0);
        check('slime bounce remains',slime.getDeltaMovement().y>0,String(slime.getDeltaMovement()));
        landing('sneaking slime ordinary damage',Blocks.SLIME_BLOCK,1.2,6,0,0,true);
        floor(Blocks.STONE);
        for(var y=240;y<=244;y++)for(var z=38;z<=43;z++)level.setBlock(new Pos(43,y,z),Blocks.STONE.defaultBlockState(),18);
        var p=pig();p.setPos(42.4,241,40.5);p.setDeltaMovement(new Vec(1.2,0,0));p.move(Move.SELF,p.getDeltaMovement());
        check('horizontal wall same curve',near(20-p.getHealth(),6),20-p.getHealth());p.discard();
        var flyer=pig();flyer.setPos(42.4,241,40.5);flyer.setDeltaMovement(new Vec(1.2,0,0));
        flyer.setYRot(-90);flyer.setXRot(0);
        // Real LivingEntity.travel Elytra branch, including its old wall-damage seam.
        var sharedFlags=C('net.neoforged.fml.util.ObfuscationReflectionHelper').getPrivateValue(
            C('net.minecraft.world.entity.Entity'),null,'DATA_SHARED_FLAGS_ID');
        flyer.getEntityData().set(sharedFlags,C('java.lang.Byte').valueOf('-128'));
        check('Elytra branch enabled',flyer.isFallFlying(),flyer.isFallFlying());
        flyer.setNoAi(false);flyer.travel(Vec.ZERO);
        // Pitch zero: -0.02 vertical produces +0.002 forward, heading correction
        // removes 0.0002, then native horizontal drag multiplies by 0.99.
        var impactSpeed=(1.2+0.002-0.0002)*0.99;
        check('Elytra uses squared pre-collision speed once',near(20-flyer.getHealth(),6.25*impactSpeed*impactSpeed-3),
            {damage:20-flyer.getHealth(),position:String(flyer.position()),velocity:String(flyer.getDeltaMovement())});
        flyer.discard();
        var side=pig();
        C('cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator').applyDirectAssignment(side,
            new (C('cc.sighs.gravityengine.gravity.GravityState'))(new (C('cc.sighs.gravityengine.api.math.Vec3d'))(1,0,0),0.08));
        side.setPos(42.2,241,40.5);side.setDeltaMovement(new Vec(1.2,0,0));side.move(Move.SELF,side.getDeltaMovement());
        check('sideways gravity impact',near(20-side.getHealth(),6),20-side.getHealth());side.discard();

        var profile=new (C('com.mojang.authlib.GameProfile'))(C('java.util.UUID').randomUUID(),'GEImpact');
        var player=new (C('net.minecraft.server.level.ServerPlayer'))(server,level,profile,C('net.minecraft.server.level.ClientInformation').createDefault());
        actors.push(player);
        var connection=new (C('net.minecraft.network.Connection'))(C('net.minecraft.network.protocol.PacketFlow').SERVERBOUND);
        var channel=new (C('io.netty.channel.embedded.EmbeddedChannel'))(connection);channels.push(channel);
        C('net.neoforged.neoforge.network.registration.NetworkRegistry').configureMockConnection(connection);
        player.connection=new (C('net.minecraft.server.network.ServerGamePacketListenerImpl'))(server,connection,player,
            C('net.minecraft.server.network.CommonListenerCookie').createInitial(profile,false));
        player.setPos(40.5,242,40.5);level.addNewPlayer(player);player.connection.resetPosition();
        var epoch=Access.cast(player).gravityengine$gravityComponent().state().applicationEpoch();
        var packet=new (C('net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Pos'))(40.5,242,40.5,false);
        var impact=new Impact(6.25,level.dimension().location(),new Pos(40,239,40));
        var payload=new Payload(level.dimension().location(),epoch,packet,Lists.of(impact));
        var buffer=new (C('net.minecraft.network.FriendlyByteBuf'))(C('io.netty.buffer.Unpooled').buffer());
        try { Payload.STREAM_CODEC.encode(buffer,payload); payload=Payload.STREAM_CODEC.decode(buffer); }
        finally {buffer.release();}
        check('impact codec round trip',payload.impacts().size()==1&&near(payload.impacts().get(0).distance(),6.25),String(payload));
        Receiver.receive(player,payload);
        check('accepted player impact exact once',near(20-player.getHealth(),3.25),20-player.getHealth());
        player.invulnerableTime=0;player.setHealth(20);player.connection.teleport(40.5,242,40.5,0,0);
        Receiver.receive(player,payload);
        check('pending teleport rejects impact',near(player.getHealth(),20),player.getHealth());
        // Direct fall damage source remains independent (ender pearl calls hurt directly).
        player.invulnerableTime=0;player.hurt(player.damageSources().fall(),5);
        check('direct fall-source damage unchanged',near(player.getHealth(),15),player.getHealth());
        result.status='PASS';
    } catch(e) {result.error=String(e);result.stack=String(e.stack);}
    finally {
        var errors=[];
        actors.forEach(function(a){try{a.discard();}catch(e){errors.push(String(e));}});
        channels.forEach(function(c){try{c.finishAndReleaseAll();}catch(e){errors.push(String(e));}});
        saved.forEach(function(b){try{level.setBlock(b.pos,b.state,18);}catch(e){errors.push(String(e));}});
        result.cleanup=errors.length?'FAIL':'PASS';if(errors.length){result.cleanupErrors=errors;result.status='FAIL';}
    }
    return JSON.stringify(result);
})();

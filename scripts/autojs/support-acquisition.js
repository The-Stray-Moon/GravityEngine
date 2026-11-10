// Live transformed server diagnostic. Explicit travel steps, not real client ticks.
(function () {
    function C(n) { return Java.loadClass(n); }
    var server=AutoJS.server(), level=AutoJS.level(), saved=[], actors=[], channels=[], checks=[];
    var result={runId:__GE_RUN.id,scenario:'support-acquisition',status:'FAIL',cleanup:'NOT RUN',
        checks:checks,observations:[],inputs:{cases:[{angle:0},{angle:45},{angle:60},{angle:80},{angle:89.9},{angle:180},{angle:45,edge:true}],steps:12,gap:"min(0.03, 0.05 * abs(cos(angle)))"},
        scope:'server-thread explicit Player.travel calls; client input/network and moving slopes NOT RUN'};
    var V=C('cc.sighs.gravityengine.api.math.Vec3d'), Vec=C('net.minecraft.world.phys.Vec3');
    var Access=C('cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess');
    var Coordinator=C('cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator');
    var State=C('cc.sighs.gravityengine.gravity.GravityState');
    var BlockPos=C('net.minecraft.core.BlockPos'), Blocks=C('net.minecraft.world.level.block.Blocks');
    function check(n,v,o) { checks.push({name:n,status:v?'PASS':'FAIL',observed:o}); if(!v)throw Error(n+': '+o); }
    function xyz(v) { return [Number(v.x),Number(v.y),Number(v.z)]; }
    try {
        check('server thread',server.isSameThread(),String(server));
        result.versions={minecraft:String(server.getServerVersion())};
        ['gravityengine','neoforge','autojs','rhino'].forEach(function(id){result.versions[id]=String(C('net.neoforged.fml.ModList').get().getModContainerById(id).get().getModInfo().getVersion());});
        for(var x=34;x<=46;x++)for(var z=37;z<=43;z++)for(var y=238;y<=246;y++) {
            var pos=new BlockPos(x,y,z); level.getChunk(x>>4,z>>4);
            if(level.getBlockEntity(pos)!=null)throw Error('occupied fixture');
            saved.push({pos:pos,state:level.getBlockState(pos)});
            level.setBlock(pos,(y==239||y==245?Blocks.STONE:Blocks.AIR).defaultBlockState(),18);
        }
        result.inputs.cases.forEach(function(testCase){
            var degrees=testCase.angle;
            var profile=new (C('com.mojang.authlib.GameProfile'))(C('java.util.UUID').randomUUID(),'GESupport'+degrees);
            var p=new (C('net.minecraft.server.level.ServerPlayer'))(server,level,profile,C('net.minecraft.server.level.ClientInformation').createDefault());actors.push(p);
            var wire=new (C('net.minecraft.network.Connection'))(C('net.minecraft.network.protocol.PacketFlow').SERVERBOUND);
            var channel=new (C('io.netty.channel.embedded.EmbeddedChannel'))(wire);channels.push(channel);
            C('net.neoforged.neoforge.network.registration.NetworkRegistry').configureMockConnection(wire);
            p.connection=new (C('net.minecraft.server.network.ServerGamePacketListenerImpl'))(server,wire,p,C('net.minecraft.server.network.CommonListenerCookie').createInitial(profile,false));
            p.setPos(40,242,40);level.addNewPlayer(p);
            var a=degrees*Math.PI/180, down=new V(Math.sin(a),-Math.cos(a),0);
            Coordinator.applyDirectAssignment(p,new State(down,0.08));p.connection.tick();
            var rt=Access.cast(p).gravityengine$gravityComponent().operationState();
            var up=rt.installedCollisionUp();
            var extent=p.getBbWidth()/2+(p.getBbHeight()-p.getBbWidth())/2*Math.abs(Math.cos(a));
            p.setPos(testCase.edge?46:40,(degrees==180?245-extent-0.03:240+extent+Math.min(0.03,0.05*Math.abs(Math.cos(a))))-p.getBbHeight()/2,40);
            p.setDeltaMovement(testCase.edge?new Vec(0.8,-0.1,0):new Vec(down.x()*0.1,down.y()*0.1,0));p.setOnGround(false);p.connection.resetPosition();
            var rows=[]; result.observations.push({angle:degrees,edge:!!testCase.edge,installedUp:String(up),steps:rows});
            for(var i=0;i<12;i++) {
                var before=p.position();
                var op=C('cc.sighs.gravityengine.gravity.integration.GravityOperation').open(p,
                    C('cc.sighs.gravityengine.gravity.model.GravityOperationType').TRAVEL,p.position());
                var path=null;
                try { p.travel(Vec.ZERO); var move=rt.currentMoveResult();
                    path=move==null?null:move.supportingContactDuringMove();
                } finally { op.close(); }
                var c=rt.completedEndpointGround();
                var rest=rt.restingContactSnapshot();
                rows.push({step:i,before:xyz(before),after:xyz(p.position()),velocity:xyz(p.getDeltaMovement()),
                    onGround:p.onGround(),verticalCollision:p.verticalCollision,
                    pathSupport:path,
                    terminalSupport:c.isPresent()?c.get().terminalSupported():null,
                    continuity:c.isPresent()?String(c.get()):null,resting:rest==null?null:String(rest)});
            }
            check('finite trajectory '+degrees,rows.every(function(r){return r.after.every(Number.isFinite)&&r.velocity.every(Number.isFinite);}),rows.length);
            check('movement executed '+degrees,rows.some(function(r){return r.before[0]!=r.after[0]||r.before[1]!=r.after[1];}),rows.length);
            if(!testCase.edge&&(degrees==45||degrees==180))
                check('support acquired in landing move '+degrees,rows[0].pathSupport&&rows[0].terminalSupport,rows[0]);
            if(degrees==60||degrees==80||degrees==89.9)
                check('steep foot support acquired '+degrees,rows.every(function(r){return r.terminalSupport===true&&r.onGround;}),rows);
            if(testCase.edge)
                check('path contact survives terminal departure',rows.some(function(r){return r.pathSupport&&!r.terminalSupport&&!r.onGround;}),rows[0]);
            if(testCase.edge)
                check('edge restores same-step gravity',rows.some(function(r){return r.pathSupport&&!r.terminalSupport&&!r.onGround&&r.velocity[1]<-0.001;}),rows[0]);
            if(!testCase.edge&&degrees>0&&degrees<90) {
                p.setDeltaMovement(Vec.ZERO);p.travel(Vec.ZERO);
                check('no new gravity slide '+degrees,p.onGround()&&p.getDeltaMovement().lengthSqr()<1e-14,xyz(p.getDeltaMovement()));
                p.setDeltaMovement(new Vec(0,0.2,0));p.travel(Vec.ZERO);
                check('outward impulse detaches '+degrees,!p.onGround()&&p.getDeltaMovement().y>0.1,xyz(p.getDeltaMovement()));
                check('no retained departure support '+degrees,!rt.completedEndpointGround().get().terminalSupported(),String(rt.completedEndpointGround()));
            }
        });
        var Policy=C('cc.sighs.gravityengine.gravity.collision.ContinuousSupportPolicy');
        var Frame=C('cc.sighs.gravityengine.gravity.GravityFrame');
        var eps=Number(C('cc.sighs.gravityengine.gravity.collision.CollisionTolerances').ENTERING_PLANE_EPSILON);
        [-2*eps,0,eps,2*eps].forEach(function(dot){
            var normal=new V(Math.sqrt(1-dot*dot),dot,0);
            check('orientation zero boundary '+dot,Policy.facesGravityUp(normal,Frame.DEFAULT)==(dot>eps),String(Policy.classify(normal,Frame.DEFAULT)));
        });
        result.status='PASS';
    } catch(e) { result.error=String(e);result.stack=String(e.stack); }
    finally {
        var errors=[];
        actors.forEach(function(p){try{p.discard();}catch(e){errors.push(String(e));}});
        channels.forEach(function(c){try{c.finishAndReleaseAll();}catch(e){errors.push(String(e));}});
        saved.forEach(function(b){try{level.setBlock(b.pos,b.state,18);}catch(e){errors.push(String(e));}});
        result.cleanup=errors.length?'FAIL':'PASS';if(errors.length){result.cleanupErrors=errors;result.status='FAIL';}
    }
    return JSON.stringify(result);
})();

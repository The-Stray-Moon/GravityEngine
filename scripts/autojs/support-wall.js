// One explicit native aiStep per real server tick, using disposable unregistered
// ServerPlayers so the normal entity loop cannot integrate them a second time.
var __GE_WALL_RESULT, __GE_WALL_ABORT;
(function () {
    function C(n) { return Java.loadClass(n); }
    var server=AutoJS.server(), level=AutoJS.level(), saved=[], actors=[], channels=[];
    var bus=C('net.neoforged.neoforge.common.NeoForge').EVENT_BUS, listener=null, finished=false;
    var V=C('cc.sighs.gravityengine.api.math.Vec3d'), Vec=C('net.minecraft.world.phys.Vec3');
    var Access=C('cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess');
    var Coordinator=C('cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator');
    var State=C('cc.sighs.gravityengine.gravity.GravityState');
    var B=C('net.minecraft.core.BlockPos'), Blocks=C('net.minecraft.world.level.block.Blocks');
    var r=__GE_WALL_RESULT={runId:__GE_RUN.id,scenario:'support-wall',status:'RUNNING',cleanup:'NOT RUN',
        inputs:{driveTicks:40,coastTicks:60,wall:'flat stone, axis aligned, block seams'},checks:[],observations:[],
        scope:'live server ticks; native aiStep; temporary ServerPlayers, no real client connection'};
    function check(n,v,o) { r.checks.push({name:n,status:v?'PASS':'FAIL',observed:o});if(!v)throw Error(n+': '+o); }
    function xyz(v) { return [Number(v.x),Number(v.y),Number(v.z)]; }
    function cleanup() {
        if(finished)return;finished=true;var errors=[];
        if(listener!=null)try{bus.unregister(listener);}catch(e){errors.push(String(e));}
        actors.forEach(function(a){try{a.player.discard();}catch(e){errors.push(String(e));}});
        channels.forEach(function(c){try{c.finishAndReleaseAll();}catch(e){errors.push(String(e));}});
        saved.forEach(function(b){try{level.setBlock(b.pos,b.state,18);}catch(e){errors.push(String(e));}});
        r.cleanup=errors.length?'FAIL':'PASS';if(errors.length){r.cleanupErrors=errors;r.status='FAIL';}
        r.columns=['step','tick','after','velocity','gap','onGround','terminal','path','normalVelocity'];
        r.observations.forEach(function(o){o.steps=o.steps.map(function(row){return r.columns.map(function(key){return row[key];});});});
    }
    __GE_WALL_ABORT=function(){r.status='FAIL';r.error='tick scenario timeout';cleanup();};
    try {
        check('server thread',server.isSameThread(),true);
        r.versions={minecraft:String(server.getServerVersion())};
        ['gravityengine','neoforge','autojs','rhino'].forEach(function(id){r.versions[id]=String(C('net.neoforged.fml.ModList').get().getModContainerById(id).get().getModInfo().getVersion());});
        // Separate wall fixtures for +/-X and +/-Z. All mutation is restored.
        [0,1,2,3].forEach(function(index){
            var alongX=index<2, sign=index%2==0?1:-1, base=80+index*64;
            for(var normal=-3;normal<=3;normal++)for(var tangent=-3;tangent<=30;tangent++)for(var y=238;y<=244;y++){
                var pos=new B(alongX?base+normal:base+tangent,y,alongX?80+tangent:80+normal);
                level.getChunk(pos.getX()>>4,pos.getZ()>>4);
                if(level.getBlockEntity(pos)!=null)throw Error('occupied fixture');
                saved.push({pos:pos,state:level.getBlockState(pos)});
                level.setBlock(pos,(normal==0?Blocks.STONE:Blocks.AIR).defaultBlockState(),18);
            }
            var profile=new (C('com.mojang.authlib.GameProfile'))(C('java.util.UUID').randomUUID(),'GEWall'+index);
            var p=new (C('net.minecraft.server.level.ServerPlayer'))(server,level,profile,C('net.minecraft.server.level.ClientInformation').createDefault());
            var a={player:p,alongX:alongX,sign:sign,base:base,rows:[]};actors.push(a);
            var wire=new (C('net.minecraft.network.Connection'))(C('net.minecraft.network.protocol.PacketFlow').SERVERBOUND);
            var channel=new (C('io.netty.channel.embedded.EmbeddedChannel'))(wire);channels.push(channel);
            C('net.neoforged.neoforge.network.registration.NetworkRegistry').configureMockConnection(wire);
            p.connection=new (C('net.minecraft.server.network.ServerGamePacketListenerImpl'))(server,wire,p,C('net.minecraft.server.network.CommonListenerCookie').createInitial(profile,false));
            var normalPos=(alongX?base:80)+(sign==1?-2:3);
            p.setPos(alongX?normalPos:base,240,alongX?80:normalPos);
            var down=alongX?new V(sign,0,0):new V(0,0,sign);
            Coordinator.applyDirectAssignment(p,new State(down,0.08));p.connection.tick();
            normalPos=(alongX?base:80)+(sign==1?-p.getBbHeight()/2-0.03:1+p.getBbHeight()/2+0.03);
            p.setPos(alongX?normalPos:base+0.37,240,alongX?80.37:normalPos);
            p.setYRot(alongX?0:-90);p.setXRot(0);
            p.setDeltaMovement(new Vec(down.x()*0.1,0,down.z()*0.1));p.setOnGround(false);p.connection.resetPosition();
            a.origin=xyz(p.position());
            a.runtime=Access.cast(p).gravityengine$gravityComponent().operationState();
            r.observations.push({direction:(sign==1?'+':'-')+(alongX?'X':'Z'),installedUp:String(a.runtime.installedCollisionUp()),steps:a.rows});
        });
        var step=0;
        listener=new (C('java.util.function.Consumer'))({accept:function(event){
            if(finished)return;
            try {
                actors.forEach(function(a){
                    var p=a.player,rt=a.runtime,before=p.position();
                    p.tickCount++;p.zza=step<40?1:0;p.xxa=0;p.yya=0;p.aiStep();
                    var endpoint=rt.completedEndpointGround();
                    var rest=rt.restingContactSnapshot();
                    var gap=a.alongX?(a.sign==1?a.base-p.getX()-p.getBbHeight()/2:p.getX()-a.base-1-p.getBbHeight()/2)
                        :(a.sign==1?80-p.getZ()-p.getBbHeight()/2:p.getZ()-81-p.getBbHeight()/2);
                    a.rows.push({step:step,tick:Number(level.getGameTime()),after:xyz(p.position()),
                        velocity:xyz(p.getDeltaMovement()),gap:gap,onGround:p.onGround(),
                        terminal:endpoint.isPresent()?endpoint.get().terminalSupported():null,
                        normalVelocity:rest==null?null:Number(a.alongX?-a.sign*p.getDeltaMovement().x:-a.sign*p.getDeltaMovement().z)});
                });
                step++;
                if(step==100){
                    actors.forEach(function(a,index){
                        var rows=a.rows, last=rows[99],first=rows[0];
                        check('100 distinct ticks '+index,new Set(rows.map(function(v){return v.tick;})).size==100,rows.length);
                        check('crossed block seams '+index,Math.abs(last.after[a.alongX?2:0]-a.origin[a.alongX?2:0])>2,last.after);
                        check('no delayed support on flat wall '+index,rows.every(function(v){return v.terminal===true;}),rows.filter(function(v){return v.terminal!==true;}).map(function(v){return v.step;}));
                    });
                    r.status='PASS';cleanup();
                }
            }catch(e){r.status='FAIL';r.error=String(e);r.stack=String(e.stack);cleanup();}
        }});
        bus.addListener(C('net.neoforged.bus.api.EventPriority').NORMAL,false,
            C('net.neoforged.neoforge.event.tick.ServerTickEvent$Post'),listener);
    }catch(e){r.status='FAIL';r.error=String(e);r.stack=String(e.stack);cleanup();}
    return JSON.stringify(r);
})();

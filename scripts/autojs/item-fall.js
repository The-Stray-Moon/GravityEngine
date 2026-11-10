var __GE_ITEM_SERVER, __GE_ITEM_SERVER_ABORT;
(function () {
    function C(n){return Java.loadClass(n);}
    var server=AutoJS.server(),level=AutoJS.level(),player=server.getPlayerList().getPlayers().get(0);
    var bus=C('net.neoforged.neoforge.common.NeoForge').EVENT_BUS,actors=[],listener,steps=0,done=false;
    var engine=C('net.neoforged.fml.ModList').get().isLoaded('gravityengine');
    var r=__GE_ITEM_SERVER={runId:__GE_RUN.id,scenario:'item-fall',status:'RUNNING',cleanup:'NOT RUN',rows:[],checks:[],
        inputs:{ticks:80,spawnHeight:250,spawnDelayMs:__GE_RUN.spawnDelayMs||0,modes:['native','direct-0.08']},scope:'real server/client tracked item free fall; no block mutations'};
    function cleanup(){if(done)return;done=true;bus.unregister(listener);actors.forEach(function(e){e.discard();});r.cleanup='PASS';}
    __GE_ITEM_SERVER_ABORT=function(){r.status='FAIL';r.error='timeout';cleanup();return JSON.stringify(r);};
    try {
        r.engineLoaded=engine;
        r.versions={minecraft:String(server.getServerVersion()),autojs:String(C('net.neoforged.fml.ModList').get().getModContainerById('autojs').get().getModInfo().getVersion())};
        if(player.isDeadOrDying())throw Error('living tracked player required');
        level=player.serverLevel();
        var Item=C('net.minecraft.world.entity.item.ItemEntity'),Stack=C('net.minecraft.world.item.ItemStack'),Items=C('net.minecraft.world.item.Items');
        for(var i=0;i<(engine?2:1);i++){
            var e=new Item(level,player.getX()+3+i*3,player.getY()+250,player.getZ(),new Stack(Items.STONE));
            e.setDeltaMovement(C('net.minecraft.world.phys.Vec3').ZERO);
            e.setCustomName(C('net.minecraft.network.chat.Component').literal('GE-item-'+__GE_RUN.id+'-'+i));
            e.setPickUpDelay(32767);actors.push(e);
            if(i==1)C('cc.sighs.gravityengine.api.GravityEngineApi').setDirectGravity(e,
                new (C('cc.sighs.gravityengine.api.math.Vec3d'))(0,-1,0),0.08);
            level.addFreshEntity(e);
        }
        // Diagnostic input only: let the replica simulate before the first server tick.
        if(r.inputs.spawnDelayMs)C('java.lang.Thread').sleep(r.inputs.spawnDelayMs);
        listener=new (C('java.util.function.Consumer'))({accept:function(event){
            try {
            if(done)return;
            actors.forEach(function(e){var s=engine?C('cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess').cast(e).gravityengine$gravityComponent().state():null;
                r.rows.push({name:String(e.getCustomName().getString()),tick:Number(e.tickCount),gameTime:Number(e.level().getGameTime()),nano:String(C('java.lang.System').nanoTime()),codecY:Number(e.getPositionCodec().decode(0,0,0).y),oldY:Number(e.yo),y:Number(e.getY()),vy:Number(e.getDeltaMovement().y),
                    epoch:s?Number(s.applicationEpoch()):null,strength:s?Number(s.appliedState().strength()):null,plan:s?String(s.appliedPlan()):'NATIVE',presence:s?String(s.fieldPresence()):null,ground:e.onGround()});});
            if(++steps>=80){r.status=actors.every(function(e){return e.tickCount>=75;})?'PASS':'FAIL';
                r.checks.push({name:'actors advanced at least 75 native ticks',status:r.status});cleanup();}
            }catch(failure){r.status='FAIL';r.error=String(failure);cleanup();}
        }});
        bus.addListener(C('net.neoforged.bus.api.EventPriority').LOWEST,false,C('net.neoforged.neoforge.event.tick.ServerTickEvent$Post'),listener);
    }catch(e){r.status='FAIL';r.error=String(e);cleanup();}
    return JSON.stringify(r);
})();

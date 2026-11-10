var __GE_ITEM_CLIENT, __GE_ITEM_CLIENT_FINISH;
(function () {
    function C(n) { return Java.loadClass(n); }
    var mc=C('net.minecraft.client.Minecraft').getInstance();
    var bus=C('net.neoforged.neoforge.common.NeoForge').EVENT_BUS;
    var rows=[], listener, prefix='GE-item-'+__GE_RUN.id,lastTick={};
    var previousPause=mc.options.pauseOnLostFocus;
    var engine=C('net.neoforged.fml.ModList').get().isLoaded('gravityengine');
    __GE_ITEM_CLIENT={status:'RUNNING',cleanup:'NOT RUN',rows:rows};
    listener=new (C('java.util.function.Consumer'))({accept:function(event){
        try {
        if(mc.level==null)return;
        var entities=mc.level.entitiesForRendering().iterator();
        while(entities.hasNext()){
        var e=entities.next();
        if(e.getCustomName()==null || !String(e.getCustomName().getString()).startsWith(prefix))continue;
        var key=String(e.getCustomName().getString());
        if(lastTick[key]===Number(e.tickCount))continue;
        lastTick[key]=Number(e.tickCount);
        var s=engine?C('cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess').cast(e).gravityengine$gravityComponent().state():null;
        rows.push({name:String(e.getCustomName().getString()),tick:Number(e.tickCount),gameTime:Number(e.level().getGameTime()),nano:String(C('java.lang.System').nanoTime()),codecY:Number(e.getPositionCodec().decode(0,0,0).y),oldY:Number(e.yo),y:Number(e.getY()),
            renderY:Number(e.getY()+mc.getEntityRenderDispatcher().getRenderer(e).getRenderOffset(e,1).y),renderOffsetY:Number(mc.getEntityRenderDispatcher().getRenderer(e).getRenderOffset(e,1).y),vy:Number(e.getDeltaMovement().y),epoch:s?Number(s.applicationEpoch()):null,strength:s?Number(s.appliedState().strength()):null,
            plan:s?String(s.appliedPlan()):'NATIVE',presence:s?String(s.fieldPresence()):null,ground:e.onGround()});
        }
        } catch(failure) {bus.unregister(listener);mc.options.pauseOnLostFocus=previousPause;__GE_ITEM_CLIENT.status='FAIL';__GE_ITEM_CLIENT.error=String(failure);__GE_ITEM_CLIENT.cleanup='PASS';}
    }});
    if(mc.level==null)throw Error('loaded client required');
    mc.options.pauseOnLostFocus=false;
    if(mc.screen!=null&&mc.screen.isPauseScreen())mc.setScreen(null);
    mc.mouseHandler.releaseMouse();
    bus.addListener(C('net.neoforged.bus.api.EventPriority').LOWEST,false,C('net.neoforged.neoforge.client.event.ClientTickEvent$Post'),listener);
    __GE_ITEM_CLIENT_FINISH=function(){bus.unregister(listener);mc.options.pauseOnLostFocus=previousPause;__GE_ITEM_CLIENT.cleanup='PASS';if(__GE_ITEM_CLIENT.status!=='FAIL')__GE_ITEM_CLIENT.status=rows.length?'PASS':'FAIL';return JSON.stringify(__GE_ITEM_CLIENT);};
    return JSON.stringify({status:'RUNNING'});
})();

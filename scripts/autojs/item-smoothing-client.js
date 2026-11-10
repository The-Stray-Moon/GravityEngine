(function(){
    function C(n){return Java.loadClass(n);}
    var mc=C('net.minecraft.client.Minecraft').getInstance(),level=mc.level,e=null;
    var r={runId:__GE_RUN.id,status:'RUNNING',cleanup:'NOT RUN',checks:[],
        scope:'live client native packet handlers and actual renderer offsets; isolated client item fixture'};
    function check(name,ok){r.checks.push({name:name,status:ok?'PASS':'FAIL'});if(!ok)throw Error(name);}
    function close(a,b){return Math.abs(a-b)<1e-8;}
    try{
        if(level==null)throw Error('loaded client required');
        e=new (C('net.minecraft.world.entity.item.ItemEntity'))(level,mc.player.getX()+3,mc.player.getY()+50,mc.player.getZ(),new (C('net.minecraft.world.item.ItemStack'))(C('net.minecraft.world.item.Items').STONE));
        e.setDeltaMovement(C('net.minecraft.world.phys.Vec3').ZERO);e.setNoGravity(true);e.setOldPosAndRot();
        level.addEntity(e);e.syncPacketPositionCodec(e.getX(),e.getY(),e.getZ());
        var renderer=mc.getEntityRenderDispatcher().getRenderer(e),conn=mc.getConnection();
        var offset=function(t){return renderer.getRenderOffset(e,t);};
        var visual=function(t){return e.yOld+(e.getY()-e.yOld)*t+offset(t).y;};
        var correction=function(dy){conn.handleMoveEntity(new (C('net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Pos'))(e.getId(),0,dy,0,false));};
        var y=e.getY(),boxY=e.getBoundingBox().minY;
        correction(3072);
        check('native packet installs physical position and box immediately',close(e.getY(),y+.75)&&close(e.getBoundingBox().minY,boxY+.75));
        check('correction does not change velocity',e.getDeltaMovement().lengthSqr()==0);
        check('render continuity at partial ticks 0, 0.5 and 1',close(visual(0),y)&&close(visual(.5),y)&&close(visual(1),y));
        var before=visual(.5);correction(1024);
        check('successive corrections retain visual continuity',close(visual(.5),before));
        var previous=Math.abs(offset(1).y);
        for(var i=0;i<6;i++){var endpoint=visual(1);level.tickNonPassenger(e);check('tick boundary continuity '+i,close(visual(0),endpoint));var next=Math.abs(offset(1).y);check('offset converges tick '+i,next<=previous+1e-8);previous=next;}
        check('history fully settles',offset(0).lengthSqr()==0&&offset(1).lengthSqr()==0);
        check('settling leaves physical position unchanged',close(e.getY(),y+1));
        var smallY=e.getY();e.setPos(e.getX(),smallY+.5,e.getZ());
        var smallPacket=new (C('net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket'))(e);
        e.setPos(e.getX(),smallY,e.getZ());var smallBefore=visual(1);conn.handleTeleportEntity(smallPacket);
        check('small absolute packet also preserves presentation',close(e.getY(),smallY+.5)&&close(visual(1),smallBefore));
        correction(2048);e.setOnGround(true);
        check('landing clears offset',offset(1).lengthSqr()==0);e.setOnGround(false);
        correction(2048);
        var targetY=e.getY()+10;e.setPos(e.getX(),targetY,e.getZ());
        var teleport=new (C('net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket'))(e);
        e.setPos(e.getX(),targetY-10,e.getZ());conn.handleTeleportEntity(teleport);
        check('large relocation snaps and clears history',close(e.getY(),targetY)&&offset(1).lengthSqr()==0);
        correction(2048);e.discard();check('removed item has no render offset',offset(1).lengthSqr()==0);
        r.status='PASS';
    }catch(error){r.status='FAIL';r.error=String(error);}
    finally{if(e!=null&&!e.isRemoved())e.discard();r.cleanup='PASS';}
    return JSON.stringify(r);
})();

(function(){
    var mc=Java.loadClass('net.minecraft.client.Minecraft').getInstance();
    var api=Java.loadClass('cc.sighs.gravityengine.api.GravityEngineApi');
    var V=Java.loadClass('cc.sighs.gravityengine.api.math.Vec3d');
    var checks=[];
    function rejects(name,action){var rejected=false;try{action();}catch(error){rejected=true;}checks.push({name:name,status:rejected?'PASS':'FAIL'});}
    if(mc.player==null)throw Error('loaded client required');
    rejects('client direct rejected',function(){api.setDirectGravity(mc.player,new V(0,-1,0),.04);});
    rejects('client release rejected',function(){api.releaseDirectGravity(mc.player);});
    return JSON.stringify({runId:__GE_RUN.id,status:checks.every(function(c){return c.status=='PASS';})?'PASS':'FAIL',cleanup:'PASS',checks:checks});
})();

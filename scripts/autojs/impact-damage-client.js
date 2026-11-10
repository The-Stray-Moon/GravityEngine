// Live client thread. Detached LocalPlayer fixture, no packets sent and no user-player edits.
(function () {
    function C(n) { return Java.loadClass(n); }
    var mc=C('net.minecraft.client.Minecraft').getInstance(),checks=[],saved=[];
    var result={runId:__GE_RUN.id,scenario:'impact-damage-client',status:'FAIL',checks:checks,cleanup:'NOT RUN',
        scope:'live LocalPlayer collision/prediction/envelope; no wire delivery or latency'};
    function check(n,v,o) { checks.push({name:n,status:v?'PASS':'FAIL',observed:String(o)});if(!v)throw Error(n+': '+o); }
    if(mc.level==null||mc.player==null) {result.status='BLOCKED';result.error='loaded client world required';result.cleanup='PASS';return JSON.stringify(result);}
    var Vec=C('net.minecraft.world.phys.Vec3'),Pos=C('net.minecraft.core.BlockPos'),Blocks=C('net.minecraft.world.level.block.Blocks');
    var Prediction=C('cc.sighs.gravityengine.client.ClientMovementPredictionScope');
    var Move=C('net.minecraft.world.entity.MoverType'),Status=C('net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$StatusOnly');
    try {
        check('client thread',mc.isSameThread(),mc.isSameThread());
        result.versions={minecraft:String(C('net.minecraft.SharedConstants').getCurrentVersion().getName())};
        ['gravityengine','neoforge','autojs'].forEach(function(id){result.versions[id]=String(C('net.neoforged.fml.ModList').get().getModContainerById(id).get().getModInfo().getVersion());});
        var x=Math.floor(mc.player.getX()),z=Math.floor(mc.player.getZ()),y=mc.level.getMaxBuildHeight()-8;
        for(var dx=-1;dx<=1;dx++)for(var dz=-1;dz<=1;dz++)for(var dy=0;dy<=3;dy++) {
            var pos=new Pos(x+dx,y+dy,z+dz);
            if(!mc.level.hasChunkAt(pos)||mc.level.getBlockEntity(pos)!=null)throw Error('fixture area unavailable');
            saved.push({pos:pos,state:mc.level.getBlockState(pos)});
            mc.level.setBlock(pos,(dy==0?Blocks.HAY_BLOCK:Blocks.AIR).defaultBlockState(),18);
        }
        var player=new (C('net.minecraft.client.player.LocalPlayer'))(mc,mc.level,mc.player.connection,
            new (C('net.minecraft.stats.StatsCounter'))(),new (C('net.minecraft.client.ClientRecipeBook'))(),false,false);
        player.input=new (C('net.minecraft.client.player.KeyboardInput'))(mc.options);
        player.setPos(x+0.5,y+1.1,z+0.5);player.setDeltaMovement(new Vec(0,-1.2,0));
        var scope=Prediction.begin(player);
        try {
            player.move(Move.SELF,player.getDeltaMovement());
            check('capture survives post-collision zero velocity',Prediction.hasImpacts(player)&&player.getDeltaMovement().y==0,player.getDeltaMovement());
            var correction=Prediction.envelope(player,new Status(true),true).payload();
            check('correction reply carries no impact',correction.impacts().isEmpty(),correction);
            var message=Prediction.envelope(player,new Status(true),false).payload();
            check('one impact in movement envelope',message.impacts().size()==1,message);
            check('pre-response equivalent distance',Math.abs(message.impacts().get(0).distance()-9)<0.0001,message);
            check('hay contact address retained',message.impacts().get(0).materialPosition().equals(new Pos(x,y,z)),message);
            check('consumed once',!Prediction.hasImpacts(player)&&Prediction.envelope(player,new Status(true),false).payload().impacts().isEmpty(),'empty');
        } finally {scope.close();}
        check('prediction released',!Prediction.predicting(player),'closed');
        result.status='PASS';
    } catch(e) {result.error=String(e);result.stack=String(e.stack);}
    finally {
        var errors=[];saved.forEach(function(b){try{mc.level.setBlock(b.pos,b.state,18);}catch(e){errors.push(String(e));}});
        result.cleanup=errors.length?'FAIL':'PASS';if(errors.length){result.cleanupErrors=errors;result.status='FAIL';}
    }
    return JSON.stringify(result);
})();

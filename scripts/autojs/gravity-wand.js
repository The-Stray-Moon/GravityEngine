// Live server only: packet boundary, provider composition, durable source roundtrip.
// Restores the source list and dirty flag; no block edits or real-client claims.
(function () {
    function C(n) { return Java.loadClass(n); }
    var server=AutoJS.server(), level=AutoJS.level(), checks=[], player=null, channel=null;
    var Data=C('cc.sighs.gravityengine.wand.WandFields');
    var Packets=C('cc.sighs.gravityengine.wand.WandPackets');
    var Request=C('cc.sighs.gravityengine.wand.WandPackets$Request');
    var Edit=C('cc.sighs.gravityengine.wand.WandEdit'), Drafts=C('cc.sighs.gravityengine.wand.WandDrafts');
    var V=C('cc.sighs.gravityengine.api.math.Vec3d'), B=C('net.minecraft.world.phys.AABB');
    var UUID=C('java.util.UUID'), Tag=C('net.minecraft.nbt.CompoundTag');
    var data=Data.get(level), original=data.snapshot(), dirty=data.isDirty();
    var dim=level.dimension().location(), fixtureReady=false;
    var result={runId:__GE_RUN.id,scenario:'gravity-wand',status:'FAIL',cleanup:'NOT RUN',checks:checks,
        inputs:{box:[37,240,38,41,244,42],strength:0.04,range:32},scope:'transformed server; native mock connection; client interaction NOT RUN'};
    function check(n,v,o){checks.push({name:n,status:v?'PASS':'FAIL',observed:String(o)});if(!v)throw Error(n+': '+o);}
    function request(action,box,strength,range){Packets.receive(player,new Request(dim,action,box==null?null:Edit.of(box),strength,range==null?32:range,false));}
    try {
        check('server thread',server.isSameThread(),server.isSameThread());
        result.versions={minecraft:String(server.getServerVersion())};
        ['gravityengine','neoforge','autojs'].forEach(function(id){result.versions[id]=String(C('net.neoforged.fml.ModList').get().getModContainerById(id).get().getModInfo().getVersion());});
        check('isolated fixture space available',original.isEmpty(),original.size());fixtureReady=true;
        var profile=new (C('com.mojang.authlib.GameProfile'))(UUID.randomUUID(),'GEWand');
        player=new (C('net.minecraft.server.level.ServerPlayer'))(server,level,profile,C('net.minecraft.server.level.ClientInformation').createDefault());
        var wire=new (C('net.minecraft.network.Connection'))(C('net.minecraft.network.protocol.PacketFlow').SERVERBOUND);
        channel=new (C('io.netty.channel.embedded.EmbeddedChannel'))(wire);
        C('net.neoforged.neoforge.network.registration.NetworkRegistry').configureMockConnection(wire);
        player.connection=new (C('net.minecraft.server.network.ServerGamePacketListenerImpl'))(server,wire,player,C('net.minecraft.server.network.CommonListenerCookie').createInitial(profile,false));
        player.setPos(39,240,30);player.setYRot(0);player.setXRot(0);
        player.setItemInHand(C('net.minecraft.world.InteractionHand').MAIN_HAND,new (C('net.minecraft.world.item.ItemStack'))(C('cc.sighs.gravityengine.wand.GravityWand').ITEM.get()));
        var box=new B(37,240,38,41,244,42), a=0.04, zero=0;
        request(1,box,a);check('survival cannot deploy',data.snapshot().isEmpty(),data.snapshot().size());
        player.setGameMode(C('net.minecraft.world.level.GameType').CREATIVE);
        request(1,new B(37,240,38,137,244,42),a);check('oversized cuboid rejected',data.snapshot().isEmpty(),data.snapshot().size());
        request(1,box,2);check('excess acceleration rejected',data.snapshot().isEmpty(),data.snapshot().size());
        request(1,box,a);check('deploy accepted',data.snapshot().size()==1,data.snapshot().size());
        var Api=C('cc.sighs.gravityengine.api.GravityEngineApi');
        var sample=Api.sample(level,new V(50,242,40));
        check('outside source is attracted toward nearest point',String(sample.coverage())=='COMPLETE'&&sample.fieldPresent()&&Math.abs(sample.acceleration().x()+0.04)<1e-9&&sample.acceleration().y()==0,sample);
        sample=Api.sample(level,new V(39,242,40));
        check('inside source is present with zero acceleration',sample.fieldPresent()&&sample.acceleration().length()==0,sample);
        var outside=Api.sample(level,new V(74,242,40));
        check('outside independent range is complete absent',String(outside.coverage())=='COMPLETE'&&!outside.fieldPresent(),outside);
        sample=Api.sample(level,new V(73,242,40));
        check('range boundary is inclusive',sample.fieldPresent(),sample);
        var saved=data.save(new Tag(),level.registryAccess());
        var copy=Data.load(saved,level.registryAccess());
        check('durable source and parameters roundtrip',copy.snapshot().equals(data.snapshot()),saved);
        check('source record has only payload',saved.getAllKeys().size()==1,saved);
        var malformedSource=saved.copy();malformedSource.getList('Fields',10).getCompound(0).remove('Range');
        var sourceRejected=false;try{Data.load(malformedSource,level.registryAccess());}catch(error){sourceRejected=true;}
        check('missing source range rejected',sourceRejected,sourceRejected);
        var discovery=Data.provider(level).blockDiscovery().bounds().get(0);
        check('block discovery covers influence beyond source',discovery.minX()==5&&discovery.maxX()==73,discovery);
        request(2,box,zero);check('crosshair source deletion',data.snapshot().isEmpty(),data.snapshot().size());
        var plane=new B(37,240,40,41,244,40);
        request(1,plane,a);check('zero thickness plane accepted',data.snapshot().size()==1,data.snapshot());
        sample=Api.sample(level,new V(39,242,45));
        check('plane normal attraction',Math.abs(sample.acceleration().z()+0.04)<1e-9&&sample.acceleration().x()==0,sample);
        sample=Api.sample(level,new V(45,242,45));
        check('finite plane edge attracts diagonally',sample.acceleration().x()<0&&sample.acceleration().z()<0&&Math.abs(sample.acceleration().length()-0.04)<1e-9,sample);
        request(2,plane,zero);check('plane can be deleted',data.snapshot().isEmpty(),data.snapshot());
        var point=new B(39,player.getEyeY(),40,39,player.getEyeY(),40);
        request(1,point,a);check('point source accepted',data.snapshot().size()==1,data.snapshot());
        sample=Api.sample(level,new V(43,player.getEyeY(),40));
        check('point attraction',Math.abs(sample.acceleration().x()+0.04)<1e-9,sample);
        request(2,point,zero);check('point can be deleted',data.snapshot().isEmpty(),data.snapshot());
        request(1,box,zero);sample=Api.sample(level,new V(50,242,40));
        check('zero strength remains present outside source',sample.fieldPresent()&&sample.acceleration().length()==0,sample);
        request(2,box,zero);sample=Api.sample(level,new V(50,242,40));
        check('removal proves absence',!sample.fieldPresent()&&String(sample.coverage())=='COMPLETE',sample);
        request(1,box,a,0);check('zero range rejected',data.snapshot().isEmpty(),data.snapshot());
        request(1,box,a,257);check('excess range rejected',data.snapshot().isEmpty(),data.snapshot());
        var Selection=C('cc.sighs.gravityengine.wand.WandSelection');
        var Direction=C('net.minecraft.core.Direction'), Vec=C('net.minecraft.world.phys.Vec3');
        var unit=new B(0,0,0,2,2,2);
        var face=Selection.pickFace(unit,new Vec(1,1,-4),new Vec(0,0,1));
        check('front face picked',face==Direction.NORTH,face);
        var resized=Selection.moveFace(unit,face,1);
        check('negative face moves independently',resized.minZ==-1&&resized.maxZ==2&&resized.minX==0&&resized.maxX==2,resized);
        resized=Selection.moveFace(unit,Direction.SOUTH,-2);
        check('face can collapse to plane',resized.minZ==0&&resized.maxZ==0,resized);
        resized=Selection.moveFace(resized,Direction.SOUTH,1);
        check('collapsed face can extrude again',resized.maxZ==1&&resized.minZ==0,resized);
        face=Selection.pickFace(unit,new Vec(1,1,1),new Vec(0,1,0));
        check('inside ray picks exit face',face==Direction.UP,face);
        face=Selection.pickFace(new B(0,0,0,2,2,0),new Vec(1,1,-4),new Vec(0,0,1));
        check('zero thickness plane remains pickable',face==Direction.NORTH,face);
        face=Selection.pickFace(new B(1,1,1,1,1,1),new Vec(1,1,-4),new Vec(0,0,1));
        check('point remains pickable',face==Direction.NORTH,face);
        var directions=[Direction.WEST,Direction.EAST,Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH];
        directions.forEach(function(d){
            var expanded=Selection.moveFace(unit,d,1);
            check('move each face '+d,Math.abs(expanded.getXsize()+expanded.getYsize()+expanded.getZsize()-7)<1e-9,expanded);
        });
        var Field=C('cc.sighs.gravityengine.gravity.field.NearestSourceGravityField'), Query=C('cc.sighs.gravityengine.api.field.GravityFieldQuery');
        var positive=new Field(new V(0,0,0),new V(2,2,2),.08,4),negative=new Field(new V(0,0,0),new V(2,2,2),-.08,4);
        var q=Query.at(new V(4,1,1)),pos=positive.sample(q).acceleration(),neg=negative.sample(q).acceleration();
        check('signed strengths have opposite equal acceleration',Math.abs(pos.x()+neg.x())<1e-12&&Math.abs(neg.length()-.08)<1e-12,neg);
        check('negative interior stays zero',negative.sample(Query.at(new V(1,1,1))).acceleration().length()==0,negative);
        request(1,box,-.08);check('negative source deploys',data.snapshot().size()==1,data.snapshot());
        check('negative source repels',Api.sample(level,new V(50,242,40)).acceleration().x()>0,Api.sample(level,new V(50,242,40)));
        request(2,box,zero);
        [[2,3],[3,2],[2,4],[3,5],[0,3],[0,0]].forEach(function(size){
            var initial=Edit.of(new B(10,10,10,10+size[0],12,10+size[1])),e=initial;
            for(var i=0;i<4;i++){
                e=e.rotate(1);var b=e.bounds();
                check('grid aligned rotation '+size+'/'+i,b.minX==Math.floor(b.minX)&&b.maxX==Math.floor(b.maxX)&&b.minZ==Math.floor(b.minZ)&&b.maxZ==Math.floor(b.maxZ),b);
            }
            check('four turns restore edit '+size,e.equals(initial)&&e.bounds().equals(initial.bounds()),e);
            [Direction.NORTH,Direction.EAST].forEach(function(d){
                var mirrored=initial.rotate(1).mirror(d);
                check('symmetric mirror preserves bounds '+size+'/'+d,mirrored.bounds().equals(initial.rotate(1).bounds()),mirrored);
                check('two mirrors restore transform '+size+'/'+d,mirrored.mirror(d).equals(initial.rotate(1)),mirrored);
            });
            check('top cannot mirror',initial.mirror(Direction.UP).equals(initial),initial);
        });
        var turn=Edit.of(new B(0,0,0,2,2,3)).rotate(1);
        check('Create clockwise mixed parity pivot',turn.bounds().equals(new B(0,0,0,3,2,2)),turn.bounds());
        var displacement=Selection.snappedDisplacement(new Vec(1,2,3),new Vec(3.2,1.2,6.7));
        check('whole drag snaps world displacement',displacement.equals(new Vec(2,-1,4)),displacement);
        var lineBox=new B(1,0,1,1,3,1);
        check('line remains pickable',Selection.pickFace(lineBox,new Vec(1,1,-4),new Vec(0,0,1))==Direction.NORTH,lineBox);
        var drafts=Drafts.get(player);
        var retained=Edit.of(box).rotate(1).mirror(Direction.NORTH);
        Packets.receive(player,new Request(dim,3,retained,.08,32,false));
        check('server owns committed transform',drafts.get(dim).equals(retained),drafts.get(dim));
        request(3,new B(1000,240,0,1001,241,1),.08);
        check('invalid draft retains authority',drafts.get(dim).equals(retained),drafts.get(dim));
        var otherDim=C('net.minecraft.resources.ResourceLocation').parse('minecraft:the_nether');
        Packets.receive(player,new Request(otherDim,3,Edit.of(box),.08,32,false));
        check('old dimension packet cannot cross dimensions',drafts.get(otherDim)==null&&drafts.get(dim).equals(retained),drafts.get(dim));
        drafts.put(otherDim,Edit.of(new B(1,2,3,4,5,6)));
        var other=new (C('net.minecraft.server.level.ServerPlayer'))(server,level,new (C('com.mojang.authlib.GameProfile'))(UUID.randomUUID(),'GEWandOther'),C('net.minecraft.server.level.ClientInformation').createDefault());
        try{
            check('players isolated',Drafts.get(other).get(dim)==null,Drafts.get(other).get(dim));
            other.copyAttachmentsFrom(player,true);
            check('native death copy retains draft',Drafts.get(other).get(dim).equals(retained),Drafts.get(other).get(dim));
            Drafts.get(other).put(dim,null);
            check('native clone is independent',drafts.get(dim).equals(retained),drafts.get(dim));
        }finally{other.discard();}
        var playerTag=new Tag();player.saveWithoutId(playerTag);
        var restored=new (C('net.minecraft.server.level.ServerPlayer'))(server,level,profile,C('net.minecraft.server.level.ClientInformation').createDefault());
        try{
            restored.load(playerTag);var rd=Drafts.get(restored);
            check('native player save/load restores both dimensions',rd.get(dim).equals(retained)&&rd.get(otherDim).equals(drafts.get(otherDim)),rd.serializeNBT(level.registryAccess()));
            check('confirmed parameters survive player reload',rd.strength()==-.08&&rd.range()==32,rd.strength());
        }finally{restored.discard();}
        player.setItemInHand(C('net.minecraft.world.InteractionHand').MAIN_HAND,new (C('net.minecraft.world.item.ItemStack'))(C('cc.sighs.gravityengine.wand.GravityWand').ITEM.get()));
        check('second wand shares player draft',Drafts.get(player).get(dim).equals(retained),Drafts.get(player).get(dim));
        request(1,box,-.08);
        request(4,null,.08);
        check('clearing draft retains deployed sources',data.snapshot().size()==1,data.snapshot());
        request(2,box,0);
        check('source save has only Fields',data.save(new Tag(),level.registryAccess()).getAllKeys().size()==1,data.snapshot());
        var cleared=drafts.serializeNBT(level.registryAccess()),loaded=new Drafts();loaded.deserializeNBT(level.registryAccess(),cleared);
        check('draft contains only source parameters',cleared.getAllKeys().size()==4,cleared);
        check('clear persists only current dimension',loaded.get(dim)==null&&loaded.get(otherDim)!=null,cleared);
        check('clear keeps confirmed signed parameters',loaded.strength()==-.08,loaded.strength());
        var buf=new (C('net.minecraft.network.FriendlyByteBuf'))(C('io.netty.buffer.Unpooled').buffer());
        try{
            var payload=new Request(dim,3,retained,-.08,32,true);
            Request.CODEC.encode(buf,payload);
            check('draft packet preserves transform and signed parameters',Request.CODEC.decode(buf).equals(payload),payload);
        }finally{buf.release();}
        var bad=cleared.copy();bad.remove('Range');var rejected=false;
        try{new Drafts().deserializeNBT(level.registryAccess(),bad);}catch(e){rejected=true;}
        check('missing draft parameter rejected',rejected,rejected);
        result.status='PASS';
    }catch(e){result.error=String(e);}
    finally {
        try{if(fixtureReady&&player!=null){while(!data.snapshot().isEmpty()){var n=data.snapshot().size();request(2,box,zero);if(data.snapshot().size()>=n)throw Error('fixture removal failed');}data.setDirty(dirty);}if(player!=null)player.discard();if(channel!=null)channel.finishAndReleaseAll();result.cleanup='PASS';}
        catch(e){result.cleanup='FAIL';result.cleanupError=String(e);}
    }
    return JSON.stringify(result);
})();

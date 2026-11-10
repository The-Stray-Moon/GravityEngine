// Owning live client thread; isolated client-preview fixture. No server mutations.
// These are native input event entry points, not physical mouse/keyboard automation.
var __GE_WAND_FINISH;
(function(){
    function C(n){return Java.loadClass(n);}
    var MC=C('net.minecraft.client.Minecraft').getInstance(),checks=[];
    var result={runId:__GE_RUN.id,scenario:'gravity-wand-client',status:'FAIL',cleanup:'NOT RUN',checks:checks,
        scope:'live client native input handlers and preview; rendered screenshots retained; physical input and multiplayer latency NOT RUN'};
    function check(n,v,o){checks.push({name:n,status:v?'PASS':'FAIL',observed:String(o)});if(!v)throw Error(n+': '+o);}
    if(MC.player==null||MC.level==null||MC.player.isDeadOrDying()){result.status='BLOCKED';result.error='A loaded client world with a living player is required';result.cleanup='PASS';return JSON.stringify(result);}
    var Client=C('cc.sighs.gravityengine.wand.WandClient'),Edit=C('cc.sighs.gravityengine.wand.WandEdit');
    var cls=Client,Reflection=C('net.neoforged.fml.util.ObfuscationReflectionHelper');
    var saved=[], fields={},deferCleanup=false;
    var oldItem=MC.player.getMainHandItem().copy(),yaw=MC.player.getYRot(),pitch=MC.player.getXRot(),oldScreen=MC.screen;
    function f(n){if(!fields[n])fields[n]=Reflection.findField(cls,n);return fields[n];}
    function get(n){return Reflection.getPrivateValue(cls,null,n);}
    function set(n,v){
        var t=String(f(n).getType().getName());
        if(t=='long')v=C('java.lang.Long').valueOf(String(v));
        Reflection.setPrivateValue(cls,null,v,n);
    }
    var names=['level','player','held','dirty','awaiting','mode','range','strength','fieldRange','additive','edit','dragEdit','start','planeNormal','depth','normal','editingFace','lastScroll','fields'];
    try{
        check('client thread',MC.isSameThread(),MC.isSameThread());
        var FieldRuntime=C('cc.sighs.gravityengine.gravity.field.GravityFieldRuntime'), rejected=false;
        try{FieldRuntime.get(MC.level);}catch(error){rejected=String(error).indexOf('server-owned')>=0;}
        check('client cannot create FIELD runtime',rejected,rejected);
        check('client owns no FIELD runtime',FieldRuntime.getIfPresent(MC.level)==null,'absent');
        result.versions={minecraft:String(C('net.minecraft.SharedConstants').getCurrentVersion().getName())};
        ['gravityengine','neoforge','autojs'].forEach(function(id){result.versions[id]=String(C('net.neoforged.fml.ModList').get().getModContainerById(id).get().getModInfo().getVersion());});
        check('no pending user gesture',!get('dirty')&&!get('awaiting')&&get('start')==null,get('start'));
        names.forEach(function(n){saved.push([n,get(n)]);});
        MC.setScreen(null);MC.player.setYRot(0);MC.player.setXRot(0);
        MC.player.setItemInHand(C('net.minecraft.world.InteractionHand').MAIN_HAND,new (C('net.minecraft.world.item.ItemStack'))(C('cc.sighs.gravityengine.wand.GravityWand').ITEM.get()));
        set('level',MC.level);set('player',MC.player);set('held',true);set('awaiting',false);
        var modes=f('mode').getType().getEnumConstants();set('mode',modes[0]);
        var expected=['MOVE_ALL','ROTATE','MIRROR','DEPLOY','MOVE_FACE'];
        expected.forEach(function(n){check('Alt wheel consumed '+n,Client.handleScroll(1,true,false),n);check('mode sequence '+n,String(get('mode'))==n,get('mode'));});
        check('ordinary wheel belongs to hotbar',!Client.handleScroll(1,false,false),'not consumed');
        var B=C('net.minecraft.world.phys.AABB'),eye=MC.player.getEyePosition();
        var box=new B(Math.floor(eye.x)-1,Math.floor(eye.y)-1,Math.floor(eye.z)+4,Math.floor(eye.x)+2,Math.floor(eye.y)+2,Math.floor(eye.z)+6);
        var edit=Edit.of(box);set('edit',edit);set('mode',modes[0]);
        Client.handleScroll(1,false,true);var face=get('editingFace');
        check('Ctrl wheel shrinks targeted north face',get('edit').bounds().minZ==box.minZ+1,get('edit').bounds());
        Client.handleScroll(1,false,true);check('face can collapse to zero thickness',get('edit').bounds().getZsize()==0,get('edit').bounds());
        Client.handleScroll(-1,false,true);check('same locked face expands after collapse',get('editingFace')==face&&get('edit').bounds().getZsize()==1,get('edit').bounds());
        set('dirty',false);set('editingFace',null);set('edit',edit);set('mode',modes[2]);
        for(var i=0;i<4;i++)Client.handleScroll(1,false,true);
        check('four input rotations restore draft',get('edit').equals(edit),get('edit'));
        set('mode',modes[3]);Client.handleScroll(1,false,true);Client.handleScroll(1,false,true);
        check('two input mirrors restore draft',get('edit').equals(edit),get('edit'));
        set('dirty',false);set('editingFace',null);
        var Mouse=C('net.neoforged.neoforge.client.event.InputEvent$MouseButton$Pre');
        var press=new Mouse(1,1,0);Client.mouse(press);
        check('RMB is captured in mirror mode',press.isCanceled()&&get('dragEdit')!=null,get('start'));
        Client.handleScroll(1,false,true);Client.tick(new (C('net.neoforged.neoforge.client.event.ClientTickEvent$Post'))());
        check('drag wheel changes view-plane depth',get('edit').bounds().minZ==box.minZ+1,get('edit').bounds());
        // Suppress fixture commit; ordinary release still runs its native handler.
        set('dirty',false);Client.mouse(new Mouse(1,0,0));check('release ends gesture',get('start')==null,get('start'));
        set('edit',null);set('mode',modes[2]);Client.mouse(new Mouse(1,1,0));
        check('empty rotate mode starts a point',get('edit')!=null&&get('edit').bounds().getSize()==0,get('edit'));
        set('dirty',false);Client.mouse(new Mouse(1,0,0));
        var Packets=C('cc.sighs.gravityengine.wand.WandPackets'),Snapshot=C('cc.sighs.gravityengine.wand.WandPackets$DraftSnapshot');
        set('edit',edit);set('mode',modes[0]);Client.mouse(new Mouse(1,1,0));set('dirty',true);
        var dimension=MC.level.dimension().location();
        Packets.clientDraft.accept(new Snapshot(dimension,edit,.08,32,false));
        check('authoritative snapshot interrupts gesture and restores confirmed edit',get('start')==null&&!get('dirty')&&get('edit').equals(edit),get('edit'));
        var foreign=C('net.minecraft.resources.ResourceLocation').parse('gravityengine:unrelated_fixture');
        Packets.clientDraft.accept(new Snapshot(foreign,null,0,1,true));
        check('foreign dimension snapshot cannot overwrite preview',get('edit').equals(edit)&&get('strength')==.08,get('edit'));
        set('edit',edit);set('mode',modes[4]);set('strength',-.08);set('fieldRange',32);set('dirty',false);set('awaiting',false);
        result.status='RUNNING';result.screenshots=[];
        var stage=0;
        __GE_WAND_FINISH=function(abort){
            try{
                if(abort)throw Error('Preview capture aborted');
                var name='wand-'+__GE_RUN.id+'-'+stage+'.png';
                var file=new (C('java.io.File'))(MC.gameDirectory,name);
                var image=C('net.minecraft.client.Screenshot').takeScreenshot(MC.getMainRenderTarget());
                try{image.writeToFile(file);}finally{image.close();}
                result.screenshots.push(String(file.getAbsolutePath()));
                if(stage++==0){set('strength',.08);return JSON.stringify(result);}
                if(stage==2){set('strength',0);return JSON.stringify(result);}
                if(stage==3){
                    set('strength',-.08);Client.mouse(new Mouse(1,1,0));
                    check('deploy opens parameter screen',MC.screen!=null&&String(MC.screen.getTitle().getString()).length>0,MC.screen);
                    return JSON.stringify(result);
                }
                result.status='PASS';
            }catch(e){result.status='FAIL';result.error=String(e);}
            cleanup();return JSON.stringify(result);
        };
        deferCleanup=true;
    }catch(e){result.status='FAIL';result.error=String(e);}
    finally{if(!deferCleanup)cleanup();}
    function cleanup(){
        try{saved.forEach(function(entry){set(entry[0],entry[1]);});MC.player.setItemInHand(C('net.minecraft.world.InteractionHand').MAIN_HAND,oldItem);MC.player.setYRot(yaw);MC.player.setXRot(pitch);MC.setScreen(oldScreen);result.cleanup='PASS';}
        catch(e){result.cleanup='FAIL';result.cleanupError=String(e);}
    }
    return JSON.stringify(result);
})();

package cc.sighs.gravityengine.wand;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

final class WandParameterScreen extends Screen {
    private final WandEdit edit;
    private final AABB box;
    private final EditBox[] values=new EditBox[2];
    private boolean additive;
    private Component error=Component.empty();
    WandParameterScreen(WandEdit edit){super(Component.translatable("wand.gravityengine.parameters"));this.edit=edit;this.box=edit.bounds();this.additive=WandClient.additive;}
    @Override protected void init(){
        String[] defaults={Double.toString(WandClient.strength),Double.toString(WandClient.fieldRange)};
        for(int i=0;i<2;i++){
            String text=values[i]==null?defaults[i]:values[i].getValue();
            values[i]=new EditBox(font,width/2-70,height/2-50+i*27,140,20,Component.translatable("wand.gravityengine."+(i==0?"strength":"range")));
            values[i].setMaxLength(24);values[i].setValue(text);addRenderableWidget(values[i]);
        }
        addRenderableWidget(Button.builder(Component.translatable("wand.gravityengine."+(additive?"additive":"override")),b->{additive=!additive;b.setMessage(Component.translatable("wand.gravityengine."+(additive?"additive":"override")));}).bounds(width/2-100,height/2+40,200,20).build());
        addRenderableWidget(Button.builder(Component.translatable("wand.gravityengine.confirm"),b->deploy()).bounds(width/2-100,height/2+68,96,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),b->onClose()).bounds(width/2+4,height/2+68,96,20).build());
    }
    private void deploy(){
        try{
            double strength=Double.parseDouble(values[0].getValue()),range=Double.parseDouble(values[1].getValue());
            if(!WandFields.valid(box,strength,range))throw new IllegalArgumentException();
            WandClient.send(1,edit,strength,range,additive);onClose();
        }catch(IllegalArgumentException ex){error=Component.translatable("wand.gravityengine.invalid");}
    }
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        super.render(g,x,y,partial);
        g.drawCenteredString(font,title,width/2,height/2-95,0xffffff);
        g.drawCenteredString(font,Component.translatable("wand.gravityengine.units"),width/2,height/2-78,0xaaaaaa);
        for(int i=0;i<2;i++)g.drawString(font,Component.translatable("wand.gravityengine."+(i==0?"strength":"range")),width/2-140,height/2-44+i*27,0xffffff);
        g.drawCenteredString(font,error,width/2,height/2+96,0xff6666);
    }
    double previewStrength(){return preview(0);}
    double previewRange(){return preview(1);}
    private double preview(int index){try{return Double.parseDouble(values[index].getValue());}catch(NumberFormatException ex){return Double.NaN;}}
    @Override public boolean isPauseScreen(){return false;}
}

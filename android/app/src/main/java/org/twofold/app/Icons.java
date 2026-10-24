package org.twofold.app;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Tiny original vector icons, drawn without an icon/font dependency. */
final class Icons extends Drawable {
    private final String kind;private final int color;private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    Icons(String kind,int color){this.kind=kind;this.color=color;}
    @Override public void draw(Canvas source){
        Canvas c=source;int save=c.save();Rect b=getBounds();c.translate(b.left,b.top);c.scale(b.width()/32f,b.height()/32f);
        pen.setColor(color);pen.setStrokeWidth(2);pen.setStyle(Paint.Style.STROKE);pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);
        switch(kind){
            case "camera":c.drawRoundRect(3,8,29,27,4,4,pen);c.drawCircle(16,17,6,pen);line(c,10,8,12,4);line(c,12,4,20,4);line(c,20,4,22,8);break;
            case "back":line(c,20,5,9,16);line(c,9,16,20,27);break;
            case "close":line(c,8,8,24,24);line(c,8,24,24,8);break;
            case "lock":c.drawRoundRect(7,14,25,29,3,3,pen);c.drawArc(10,3,22,23,180,180,false,pen);line(c,16,20,16,23);break;
            case "swap":line(c,4,10,27,10);line(c,22,5,27,10);line(c,27,10,22,15);line(c,28,23,5,23);line(c,5,23,10,18);line(c,5,23,10,28);break;
            case "refresh":c.drawArc(5,5,27,27,30,290,false,pen);line(c,26,4,26,12);line(c,26,12,18,12);break;
            case "more":pen.setStyle(Paint.Style.FILL);for(int y=7;y<28;y+=9)c.drawCircle(16,y,2,pen);break;
            case "settings":c.drawCircle(16,16,9,pen);c.drawCircle(16,16,3,pen);for(int i=0;i<8;i++){c.save();c.rotate(i*45,16,16);line(c,16,3,16,7);c.restore();}break;
            default:
                c.drawRoundRect(3,3,29,29,5,5,pen);float x=kind.endsWith("right")?19:6,y=kind.startsWith("bottom")?19:6;c.drawRoundRect(x,y,x+7,y+7,2,2,pen);
        }
        c.restoreToCount(save);
    }
    private void line(Canvas c,float x,float y,float xx,float yy){c.drawLine(x,y,xx,yy,pen);}
    @Override public void setAlpha(int alpha){pen.setAlpha(alpha);}
    @Override public void setColorFilter(ColorFilter filter){pen.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}

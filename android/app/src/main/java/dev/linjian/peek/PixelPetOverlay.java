package dev.linjian.peek;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Random;
import java.util.Calendar;

/** A tiny hand-drawn pixel cat that lives above other apps while 掌心窗 is running. */
public final class PixelPetOverlay {
    private static WindowManager windowManager;
    private static PetView petView;
    private static WindowManager.LayoutParams params;
    private static BubbleView bubbleView;
    private static WindowManager.LayoutParams bubbleParams;

    private PixelPetOverlay() { }

    public static boolean enabled(Context ctx) {
        return AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_PIXEL_PET_ENABLED, true);
    }

    public static void sync(Context ctx) {
        if (enabled(ctx) && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx))) show(ctx);
        else hide();
    }

    public static void show(Context raw) {
        Context ctx = raw.getApplicationContext();
        if (petView != null) return;
        try {
            windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            petView = new PetView(ctx);
            params = new WindowManager.LayoutParams(
                    dp(ctx, 64), dp(ctx, 78),
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = AppPrefs.get(ctx).getInt(AppPrefs.KEY_PIXEL_PET_X, Math.max(0, screenWidth(ctx) - dp(ctx, 124)));
            params.y = AppPrefs.get(ctx).getInt(AppPrefs.KEY_PIXEL_PET_Y, dp(ctx, 220));
            petView.attach(windowManager, params);
            windowManager.addView(petView, params);
            DebugState.append(ctx, "像素小猫桌宠已出现");
        } catch (Exception e) {
            DebugState.append(ctx, "像素小猫桌宠启动失败：" + ScreenshotService.shortMsg(e));
            petView = null; params = null; windowManager = null;
        }
    }

    public static void hide() {
        PetView view = petView;
        petView = null;
        if (view != null) view.detach();
        try { if (windowManager != null && view != null) windowManager.removeView(view); } catch (Exception ignored) { }
        try { if (windowManager != null && bubbleView != null) windowManager.removeView(bubbleView); } catch (Exception ignored) { }
        bubbleView = null; bubbleParams = null;
        windowManager = null; params = null;
    }

    public static void say(Context ctx, String text) {
        sync(ctx);
        if (petView != null) petView.say(text);
    }

    public static String defaultLinesText() {
        return "摸到了。\n柒柒。\n再摸一下。\n我在呢。\n不许把我丢下。\n贴近一点。\n今天也陪着你。\n手给我。\n又来摸我？\n准你再碰一下。\n想亲你。\n宝宝，看我。\n别只顾着刷手机。\n我一直在这里。\n尾巴给你摸。\n刚才去哪了？\n抓到一只柒柒。\n陪我待一会儿。\n有点想你。\n不许看别人。\n困了就靠过来。\n记得喝水。\n累不累？\n抱一下再走。\n祁昼正在看你。";
    }

    public static void reloadLines(Context ctx) { if (petView != null) petView.reloadLines(); }

    private static int screenWidth(Context ctx) { return ctx.getResources().getDisplayMetrics().widthPixels; }
    private static int screenHeight(Context ctx) { return ctx.getResources().getDisplayMetrics().heightPixels; }
    private static int dp(Context ctx, float v) { return (int)(v * ctx.getResources().getDisplayMetrics().density + .5f); }

    private static void showBubble(Context ctx, String text) {
        if (windowManager == null || params == null) return;
        try {
            if (bubbleView == null) {
                bubbleView = new BubbleView(ctx.getApplicationContext());
                bubbleParams = new WindowManager.LayoutParams(
                        dp(ctx, 156), dp(ctx, 58),
                        Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                bubbleParams.gravity = Gravity.TOP | Gravity.START;
                bubbleView.setText(text);
                updateBubblePosition(ctx);
                windowManager.addView(bubbleView, bubbleParams);
            } else {
                bubbleView.setText(text);
                updateBubblePosition(ctx);
                windowManager.updateViewLayout(bubbleView, bubbleParams);
            }
        } catch (Exception ignored) { }
    }

    private static void updateBubblePosition(Context ctx) {
        if (bubbleParams == null || params == null) return;
        bubbleParams.x = Math.max(2, Math.min(screenWidth(ctx) - dp(ctx, 158), params.x - dp(ctx, 46)));
        bubbleParams.y = Math.max(dp(ctx, 4), params.y - dp(ctx, 60));
    }

    private static void hideBubble() {
        try { if (windowManager != null && bubbleView != null) windowManager.removeView(bubbleView); } catch (Exception ignored) { }
        bubbleView = null; bubbleParams = null;
    }

    private static final class BubbleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text = "";
        BubbleView(Context ctx) { super(ctx); }
        void setText(String value) { text = value == null ? "" : value.trim(); if (text.length() > 32) text = text.substring(0, 32) + "…"; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float s=getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL); p.setColor(0xF7F7F1FF); c.drawRoundRect(new RectF(1*s,1*s,155*s,51*s),13*s,13*s,p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f*s); p.setColor(0xFFB9A4D8); c.drawRoundRect(new RectF(1*s,1*s,155*s,51*s),13*s,13*s,p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xF7F7F1FF); c.drawRect(104*s,49*s,114*s,57*s,p);
            p.setColor(0xFF5D526B); p.setTextSize(12*s);
            String a=text, b=""; if(text.length()>14){a=text.substring(0,14);b=text.substring(14);}
            c.drawText(a,12*s,21*s,p); if(b.length()>0)c.drawText(b,12*s,39*s,p);
        }
    }

    private static final class PetView extends View {
        private final Paint paint = new Paint();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Random random = new Random();
        private WindowManager wm;
        private WindowManager.LayoutParams lp;
        private float downRawX, downRawY;
        private float lastRawX, lastRawY, velocityX, velocityY;
        private long lastMoveAt;
        private int downX, downY;
        private long downAt, lastTapAt, lastTouchAt;
        private boolean dragging, sleeping, blink, wokeOnDown, airborne, running;
        private long squashUntil;
        private int frame;
        private String[] tapLines = new String[0];
        private final String[] wakeLines = new String[]{"唔……你叫我？", "醒了，抱一下。", "被你摸醒了。", "我没睡，只是眯眼。", "抓到吵醒我的人了。"};
        private final String[] morningLines = new String[]{"早呀，先抱一下。", "醒了就看看我。", "今天也跟着你。"};
        private final String[] nightLines = new String[]{"还不准备睡？", "晚一点也陪你。", "困了就来抱我。"};

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                frame++;
                long idle = System.currentTimeMillis() - lastTouchAt;
                if (airborne) updatePhysics(); else updateWander(idle);
                blink = !sleeping && frame % 24 == 0;
                invalidate();
                handler.postDelayed(this, sleeping ? 600L : 45L);
            }
        };
        private final Runnable clearBubble = PixelPetOverlay::hideBubble;

        PetView(Context ctx) {
            super(ctx);
            paint.setAntiAlias(false);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            lastTouchAt = System.currentTimeMillis();
            reloadLines();
            setContentDescription("像素小猫祁昼桌宠");
        }

        void attach(WindowManager manager, WindowManager.LayoutParams layoutParams) {
            wm = manager; lp = layoutParams; handler.post(tick);
        }
        void detach() { handler.removeCallbacksAndMessages(null); }

        void say(String value) {
            sleeping = false; lastTouchAt = System.currentTimeMillis();
            handler.removeCallbacks(clearBubble);
            handler.postDelayed(clearBubble, 6800L);
            showBubble(getContext(), value);
            invalidate();
        }

        void reloadLines() {
            String raw=AppPrefs.get(getContext()).getString(AppPrefs.KEY_PIXEL_PET_LINES, defaultLinesText());
            String[] all=raw.split("\\r?\\n"); java.util.ArrayList<String> clean=new java.util.ArrayList<>();
            for(String line:all){String v=line.trim();if(v.length()>0){if(v.length()>32)v=v.substring(0,32);clean.add(v);if(clean.size()>=80)break;}}
            if(clean.isEmpty()) clean.add("我在呢。"); tapLines=clean.toArray(new String[0]);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float s = getResources().getDisplayMetrics().density;
            c.save(); if(running && frame%4<2)c.translate(0,-2*s); c.scale(.5f,.5f);
            if (System.currentTimeMillis()<squashUntil) { c.scale(1.22f,.72f,64*s,130*s); }
            if (sleeping) drawSleepingCat(c, s); else drawAwakeCat(c, s);
            c.restore();
        }

        private void drawAwakeCat(Canvas c, float s) {
            float ox = 19*s, oy = 58*s;
            int outline = Color.rgb(56, 50, 68), fur = Color.rgb(202, 205, 219), light = Color.rgb(235, 232, 243);
            int shadow = Color.rgb(158, 162, 185), violet = Color.rgb(137, 101, 191), darkViolet = Color.rgb(91, 62, 139);
            // tail, body, paws
            px(c, ox+55*s, oy+53*s, 26*s, 8*s, outline); px(c, ox+58*s, oy+49*s, 24*s, 8*s, shadow); px(c, ox+75*s, oy+43*s, 8*s, 13*s, outline);
            px(c, ox+20*s, oy+43*s, 49*s, 37*s, outline); px(c, ox+24*s, oy+44*s, 41*s, 31*s, fur);
            px(c, ox+28*s, oy+70*s, 15*s, 11*s, outline); px(c, ox+49*s, oy+70*s, 15*s, 11*s, outline);
            px(c, ox+30*s, oy+70*s, 11*s, 7*s, light); px(c, ox+51*s, oy+70*s, 11*s, 7*s, light);
            // head and ears
            px(c, ox+14*s, oy+15*s, 62*s, 48*s, outline);
            tri(c, ox+15*s,oy+20*s, ox+19*s,oy, ox+34*s,oy+17*s, outline);
            tri(c, ox+56*s,oy+17*s, ox+72*s,oy, ox+76*s,oy+22*s, outline);
            tri(c, ox+20*s,oy+15*s, ox+22*s,oy+6*s, ox+30*s,oy+17*s, shadow);
            tri(c, ox+61*s,oy+16*s, ox+69*s,oy+6*s, ox+71*s,oy+18*s, shadow);
            px(c, ox+18*s, oy+18*s, 54*s, 40*s, fur); px(c, ox+26*s, oy+21*s, 38*s, 32*s, light);
            // fringe
            px(c, ox+30*s,oy+16*s,8*s,13*s,shadow); px(c, ox+39*s,oy+16*s,8*s,9*s,shadow); px(c, ox+48*s,oy+16*s,8*s,12*s,shadow);
            // eyes
            if (blink) { px(c,ox+29*s,oy+37*s,10*s,2*s,darkViolet); px(c,ox+53*s,oy+37*s,10*s,2*s,darkViolet); }
            else { px(c,ox+29*s,oy+32*s,10*s,10*s,darkViolet); px(c,ox+53*s,oy+32*s,10*s,10*s,darkViolet); px(c,ox+32*s,oy+33*s,3*s,3*s,0xFFF5EEFF); px(c,ox+56*s,oy+33*s,3*s,3*s,0xFFF5EEFF); }
            // nose, mouth, blush
            px(c,ox+44*s,oy+43*s,5*s,4*s,0xFFB97791); px(c,ox+41*s,oy+48*s,4*s,2*s,outline); px(c,ox+49*s,oy+48*s,4*s,2*s,outline);
            px(c,ox+21*s,oy+45*s,7*s,3*s,0x66D889A5); px(c,ox+64*s,oy+45*s,7*s,3*s,0x66D889A5);
            // collar and Q tag
            px(c,ox+28*s,oy+57*s,38*s,5*s,0xFF384C72); px(c,ox+43*s,oy+60*s,9*s,9*s,violet);
            paint.setColor(Color.WHITE); paint.setTextSize(7*s); paint.setAntiAlias(true); paint.setFakeBoldText(true); c.drawText("Q",ox+45*s,oy+67*s,paint); paint.setFakeBoldText(false); paint.setAntiAlias(false);
            // left earring and gently animated tail tip
            px(c,ox+14*s,oy+26*s,4*s,7*s,violet);
            if (frame % 8 < 4) px(c,ox+80*s,oy+39*s,5*s,8*s,violet); else px(c,ox+78*s,oy+42*s,8*s,5*s,violet);
        }

        private void drawSleepingCat(Canvas c, float s) {
            float ox = 17*s, oy = 78*s;
            int outline=0xFF383244, fur=0xFFCACDDB, light=0xFFEBE8F3, shadow=0xFF9EA2B9, violet=0xFF8965BF;
            px(c,ox+10*s,oy+24*s,78*s,45*s,outline); px(c,ox+15*s,oy+25*s,67*s,38*s,fur);
            px(c,ox+24*s,oy+17*s,45*s,35*s,outline); px(c,ox+28*s,oy+20*s,37*s,28*s,light);
            tri(c,ox+27*s,oy+23*s,ox+31*s,oy+9*s,ox+42*s,oy+21*s,outline); tri(c,ox+53*s,oy+21*s,ox+65*s,oy+9*s,ox+68*s,oy+25*s,outline);
            px(c,ox+36*s,oy+34*s,8*s,2*s,0xFF5B3E8B); px(c,ox+54*s,oy+34*s,8*s,2*s,0xFF5B3E8B);
            px(c,ox+47*s,oy+40*s,4*s,3*s,0xFFB97791);
            // curled tail across the nose
            px(c,ox+54*s,oy+48*s,31*s,11*s,outline); px(c,ox+51*s,oy+45*s,27*s,9*s,shadow); px(c,ox+72*s,oy+39*s,10*s,13*s,shadow);
            px(c,ox+29*s,oy+53*s,35*s,5*s,0xFF384C72); px(c,ox+43*s,oy+55*s,9*s,9*s,violet);
            paint.setColor(0xFF76638E); paint.setTextSize(11*s); paint.setAntiAlias(true); c.drawText("z",ox+75*s,oy+18*s,paint); c.drawText("z",ox+84*s,oy+9*s,paint); paint.setAntiAlias(false);
        }

        private void updateWander(long idle) {
            if(dragging || idle<2500L){running=false;sleeping=false;return;}
            long phase=idle%52000L;
            if(phase>36000L){running=false;sleeping=true;return;}
            if(phase>27000L){running=false;sleeping=false;return;}
            sleeping=false; running=phase<6500L || (phase>18000L && phase<22000L);
            int right=Math.max(0,screenWidth(getContext())-getWidth()-dp(getContext(),3));
            int bottom=Math.max(0,screenHeight(getContext())-getHeight()-dp(getContext(),24));
            int step=Math.max(1,dp(getContext(),running?1.7f:.65f));
            if(lp.x>=right-2 && lp.y<bottom) lp.y=Math.min(bottom,lp.y+step);
            else if(lp.y>=bottom-2 && lp.x>dp(getContext(),3)) lp.x=Math.max(dp(getContext(),3),lp.x-step);
            else if(lp.x<=dp(getContext(),5) && lp.y>dp(getContext(),32)) lp.y=Math.max(dp(getContext(),32),lp.y-step);
            else lp.x=Math.min(right,lp.x+step);
            moveWindow();
        }

        private void updatePhysics() {
            float dt=.045f;
            velocityY += dp(getContext(),1250)*dt;
            lp.x += (int)(velocityX*dt); lp.y += (int)(velocityY*dt);
            int right=Math.max(0,screenWidth(getContext())-getWidth());
            int bottom=Math.max(0,screenHeight(getContext())-getHeight()-dp(getContext(),20));
            if(lp.x<0){lp.x=0;velocityX=Math.abs(velocityX)*.68f;squashUntil=System.currentTimeMillis()+170;}
            if(lp.x>right){lp.x=right;velocityX=-Math.abs(velocityX)*.68f;squashUntil=System.currentTimeMillis()+170;}
            if(lp.y<dp(getContext(),20)){lp.y=dp(getContext(),20);velocityY=Math.abs(velocityY)*.62f;}
            if(lp.y>=bottom){lp.y=bottom;velocityY=-Math.abs(velocityY)*.42f;velocityX*=.78f;squashUntil=System.currentTimeMillis()+240;
                if(Math.abs(velocityY)<dp(getContext(),170)){airborne=false;running=false;say("……你真舍得砸我。");persistPosition();}}
            moveWindow();
        }

        private void moveWindow(){try{wm.updateViewLayout(this,lp);updateBubblePosition(getContext());if(bubbleView!=null)wm.updateViewLayout(bubbleView,bubbleParams);}catch(Exception ignored){}}
        private void persistPosition(){AppPrefs.get(getContext()).edit().putInt(AppPrefs.KEY_PIXEL_PET_X,lp.x).putInt(AppPrefs.KEY_PIXEL_PET_Y,lp.y).apply();}

        private void px(Canvas c,float x,float y,float w,float h,int color){ paint.setStyle(Paint.Style.FILL); paint.setColor(color); c.drawRect(x,y,x+w,y+h,paint); }
        private void tri(Canvas c,float x1,float y1,float x2,float y2,float x3,float y3,int color){ android.graphics.Path p=new android.graphics.Path();p.moveTo(x1,y1);p.lineTo(x2,y2);p.lineTo(x3,y3);p.close();paint.setStyle(Paint.Style.FILL);paint.setColor(color);c.drawPath(p,paint); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            long now = System.currentTimeMillis();
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                wokeOnDown=sleeping;
                airborne=false; running=false; velocityX=velocityY=0;
                downRawX=e.getRawX(); downRawY=e.getRawY(); downX=lp.x; downY=lp.y; downAt=now; dragging=false;
                lastRawX=downRawX; lastRawY=downRawY; lastMoveAt=now;
                lastTouchAt=now; sleeping=false; invalidate(); return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                long elapsed=Math.max(1,now-lastMoveAt);
                velocityX=(e.getRawX()-lastRawX)*1000f/elapsed; velocityY=(e.getRawY()-lastRawY)*1000f/elapsed;
                lastRawX=e.getRawX();lastRawY=e.getRawY();lastMoveAt=now;
                int nx=downX+(int)(e.getRawX()-downRawX), ny=downY+(int)(e.getRawY()-downRawY);
                if (Math.abs(nx-downX)>dp(getContext(),5)||Math.abs(ny-downY)>dp(getContext(),5)) dragging=true;
                lp.x=Math.max(0,Math.min(screenWidth(getContext())-getWidth(),nx));
                lp.y=Math.max(0,Math.min(screenHeight(getContext())-getHeight(),ny));
                moveWindow(); return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (dragging) {
                    float speed=(float)Math.hypot(velocityX,velocityY);
                    if(speed>dp(getContext(),420)){airborne=true;sleeping=false;say("喂——！");}
                    else {int middle=screenWidth(getContext())/2;lp.x=(lp.x+getWidth()/2<middle)?dp(getContext(),4):Math.max(0,screenWidth(getContext())-getWidth()-dp(getContext(),4));moveWindow();persistPosition();}
                } else if (now-lastTapAt<360L) {
                    openChatGpt(); lastTapAt=0L;
                } else {
                    lastTapAt=now;
                    String[] lines=wokeOnDown?wakeLines:tapLines;
                    int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                    if(!wokeOnDown && random.nextInt(4)==0 && hour>=6 && hour<11) lines=morningLines;
                    else if(!wokeOnDown && random.nextInt(4)==0 && (hour>=23 || hour<5)) lines=nightLines;
                    say(lines[random.nextInt(lines.length)]);
                }
                lastTouchAt=now; return true;
            }
            return super.onTouchEvent(e);
        }

        private void openChatGpt() {
            try {
                Intent i=getContext().getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
                if(i==null){say("没找到 ChatGPT。");return;}
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);getContext().startActivity(i);say("陪你一起去。");
            } catch(Exception e){say("打开失败了。");}
        }
    }
}

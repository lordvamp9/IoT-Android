package com.inacap.iotclima

import android.animation.ValueAnimator
import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** Native OpenGL scene: perspective camera, lit meshes and depth-tested architecture. */
class AgroSimulation3DView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    @Volatile var isManualMode = false; private set
    @Volatile var isVentilationOpen = false; private set
    @Volatile var isPumpActive = false; private set
    var exactTemperature = 0.0; private set
    var exactHumidity = 0.0; private set
    private val scene = GreenhouseRenderer()
    private var gateAnimator: ValueAnimator? = null
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var resumed = true
    private val frames = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isShown || !resumed) return
            requestRender()
            if (isPumpActive || isVentilationOpen) postDelayed(this, 33)
        }
    }
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8,8,8,8,24,0)
        preserveEGLContextOnPause = true
        setRenderer(scene)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "Invernadero 3D. Arrastra horizontalmente para girar y verticalmente para inclinar."
    }
    fun setManualMode(enabled: Boolean) { isManualMode=enabled; requestRender() }
    fun updateTelemetry(temperature: Double, humidity: Double) {
        exactTemperature=temperature; exactHumidity=humidity; requestRender()
    }
    fun setPump(active: Boolean) { isPumpActive=active; scene.pump=active; refreshFrames() }
    fun setVentilation(open: Boolean) {
        if (isVentilationOpen==open) return
        isVentilationOpen=open; gateAnimator?.cancel()
        gateAnimator=ValueAnimator.ofFloat(scene.gate,if(open) 1f else 0f).apply {
            duration=800
            addUpdateListener { scene.gate=it.animatedValue as Float; requestRender() }
            start()
        }
        refreshFrames()
    }
    private fun refreshFrames() { removeCallbacks(frames); post(frames) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX=event.x;lastY=event.y;moved=false
                // Keep the entire orbit gesture inside the scene, including all ancestors.
                parent?.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx=event.x-lastX;val dy=event.y-lastY
                moved=moved || abs(dx)+abs(dy)>3f
                scene.yaw += dx/max(width,1)*3.2f
                scene.pitch=(scene.pitch+dy/max(height,1)*1.2f).coerceIn(.2f,1.05f)
                lastX=event.x;lastY=event.y;requestRender();return true
            }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false);if(!moved)performClick();return true }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false);return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility==View.VISIBLE && !resumed) { onResume();resumed=true;refreshFrames() }
        else if (visibility!=View.VISIBLE && resumed) { removeCallbacks(frames);onPause();resumed=false }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();if(!resumed){onResume();resumed=true};refreshFrames() }
    override fun onDetachedFromWindow() { removeCallbacks(frames);gateAnimator?.cancel();super.onDetachedFromWindow() }

    private class GreenhouseRenderer : Renderer {
        @Volatile var yaw=.65f
        @Volatile var pitch=.52f
        @Volatile var gate=0f
        @Volatile var pump=false
        private var program=0
        private var pos=0;private var normal=0;private var mvpLoc=0;private var modelLoc=0;private var colorLoc=0
        private var normalLoc=0;private var eyeLoc=0
        private lateinit var box: Mesh
        private lateinit var ball: Mesh
        private val projection=FloatArray(16)
        private val view=FloatArray(16)
        private val vp=FloatArray(16)
        private val model=FloatArray(16)
        private val mvp=FloatArray(16)
        private val inverse=FloatArray(16)
        private val normalMatrix=FloatArray(16)
        private data class Item(val x:Float,val y:Float,val z:Float,val sx:Float,val sy:Float,val sz:Float,
                                val color:FloatArray,val ry:Float=0f,val rz:Float=0f,val round:Boolean=false,val rx:Float=0f)
        private val architecture=ArrayList<Item>()
        private fun color(hex:Int,alpha:Float=1f)=floatArrayOf((hex shr 16 and 255)/255f,(hex shr 8 and 255)/255f,(hex and 255)/255f,alpha)
        private fun part(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,c:Int,ry:Float=0f,rz:Float=0f,round:Boolean=false) {
            architecture.add(Item(x,y,z,sx,sy,sz,color(c),ry,rz,round))
        }
        override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
            val vertex="""attribute vec3 aPosition; attribute vec3 aNormal; uniform mat4 uMvp; uniform mat4 uModel; uniform mat4 uNormal;
                varying vec3 vNormal; varying vec3 vWorld;
                void main(){vWorld=(uModel*vec4(aPosition,1.0)).xyz;vNormal=normalize(mat3(uNormal)*aNormal);gl_Position=uMvp*vec4(aPosition,1.0);}"""
            val fragment="""precision mediump float;uniform vec4 uColor;uniform vec3 uEye;varying vec3 vNormal;varying vec3 vWorld;
                void main(){vec3 n=normalize(vNormal);vec3 l=normalize(vec3(-0.6,1.0,0.7));float d=max(dot(n,l),0.0);
                vec3 e=normalize(uEye-vWorld);float rim=pow(1.0-abs(dot(n,e)),3.0)*0.07;
                float spec=pow(max(dot(n,normalize(l+e)),0.0),42.0)*0.17;
                vec3 col=uColor.rgb*(0.42+0.58*d)+vec3(rim+spec);
                gl_FragColor=vec4(col,uColor.a);}"""
            fun shader(type:Int,source:String):Int {val id=glCreateShader(type);glShaderSource(id,source);glCompileShader(id);val ok=IntArray(1);glGetShaderiv(id,GL_COMPILE_STATUS,ok,0);check(ok[0]!=0){glGetShaderInfoLog(id)};return id}
            program=glCreateProgram();val vs=shader(GL_VERTEX_SHADER,vertex);val fs=shader(GL_FRAGMENT_SHADER,fragment)
            glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program)
            val linked=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,linked,0);check(linked[0]!=0){glGetProgramInfoLog(program)}
            glDeleteShader(vs);glDeleteShader(fs)
            pos=glGetAttribLocation(program,"aPosition");normal=glGetAttribLocation(program,"aNormal")
            mvpLoc=glGetUniformLocation(program,"uMvp");modelLoc=glGetUniformLocation(program,"uModel");colorLoc=glGetUniformLocation(program,"uColor")
            normalLoc=glGetUniformLocation(program,"uNormal");eyeLoc=glGetUniformLocation(program,"uEye")
            box=Mesh.cube();ball=Mesh.sphere();buildArchitecture()
            glEnable(GL_DEPTH_TEST);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA)
            glClearColor(.035f,.075f,.105f,1f)
        }
        private fun buildArchitecture() {
            architecture.clear()
            part(0f,-.25f,0f,5.7f,.28f,3.8f,0x244858)
            part(0f,-.08f,0f,5.5f,.1f,3.6f,0xA9C4C7)
            // Walkways and raised timber beds with inset soil, rim and repeated boards.
            for(row in -1..1) {
                val z=row*1.05f
                part(-.15f,.07f,z,4.15f,.19f,.76f,0x755845)
                part(-.15f,.18f,z,4.02f,.025f,.64f,0x473F30)
                for(side in listOf(-1,1)) part(-.15f,.2f,z+side*.37f,4.2f,.08f,.055f,0xAF8A62)
                for(j in 0..14) part(-2.15f+j*.285f,.08f,z+.39f,.017f,.19f,.015f,0x493C32)
                for(j in 0..6) {
                    val x=-1.9f+j*.58f
                    part(x,.37f,z,.028f,.38f,.028f,0x4C8B51)
                    // Volumetric elliptical leaves on four levels, each with a central vein.
                    for(leaf in 0..5) {
                        val a=leaf*137.5f+j*13f;val rad=a*PI.toFloat()/180
                        val y=.31f+leaf*.052f
                        part(x+cos(rad)*.105f,y,z+sin(rad)*.105f,.3f,.065f,.13f,
                            if(leaf%2==0)0x42B981 else 0x84CE8C,ry=-a,rz=if(cos(rad)>0)22f else -22f,round=true)
                    }
                }
            }
            // Columns, pitched rafters, roof purlins and horizontal bracing.
            for(z in listOf(-1.65f,0f,1.65f)) {
                for(x in listOf(-2.55f,2.55f)) {
                    part(x,1.15f,z,.065f,2.3f,.065f,0x96BCC7)
                    part(x,.05f,z,.17f,.1f,.17f,0x3C6579)
                }
                val length=sqrt(2.55f*2.55f+.85f*.85f)
                val angle=atan2(.85f,2.55f)*180f/PI.toFloat()
                part(-1.275f,2.725f,z,length,.065f,.065f,0xB9D3DB,rz=angle)
                part(1.275f,2.725f,z,length,.065f,.065f,0xB9D3DB,rz=-angle)
            }
            for(x in listOf(-2.55f,-1.3f,0f,1.3f,2.55f)) {
                val y=3.15f-abs(x)/2.55f*.85f
                part(x,y,0f,.045f,.045f,3.36f,0x6C96A7)
            }
            for(x in listOf(-2.55f,2.55f)) for(y in listOf(.65f,1.6f,2.3f)) part(x,y,0f,.035f,.035f,3.3f,0x658C9D)
            // Rear wall vent housing and visible blue SG90 unit.
            part(-1.2f,1.56f,-1.64f,1.4f,.9f,.07f,0x27485C)
            part(-2f,1.54f,-1.57f,.18f,.32f,.22f,0x437DC6)
            part(-1.97f,1.54f,-1.4f,.13f,.04f,.04f,0xE8EDEA)
            // Water tank, pump unit and overhead irrigation manifold.
            part(2.18f,.43f,.9f,.46f,.85f,.62f,0x2B998F)
            part(2.18f,.89f,.9f,.49f,.09f,.64f,0xB9DAD9)
            part(2.2f,1.37f,.9f,.055f,.95f,.055f,0x87B8CD)
            part(.05f,1.85f,.1f,4.35f,.06f,.06f,0x98C8D4)
            part(2.2f,1.85f,.5f,.055f,.055f,.85f,0x98C8D4)
            for(x in listOf(-1.4f,0f,1.4f)) part(x,1.79f,.1f,.11f,.1f,.11f,0x244F68)
            part(2.46f,.66f,-.7f,.16f,.5f,.42f,0x315775)
            for(i in 0..2) part(2.56f,.78f-i*.1f,-.7f,.02f,.035f,.035f,0x74E9BD)
        }
        override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) {glViewport(0,0,w,h);Matrix.perspectiveM(projection,0,39f,w.toFloat()/max(h,1),.1f,60f)}
        private fun draw(item:Item) {
            Matrix.setIdentityM(model,0);Matrix.translateM(model,0,item.x,item.y,item.z)
            Matrix.rotateM(model,0,item.ry,0f,1f,0f);Matrix.rotateM(model,0,item.rz,0f,0f,1f)
            Matrix.rotateM(model,0,item.rx,1f,0f,0f);Matrix.scaleM(model,0,item.sx,item.sy,item.sz)
            Matrix.multiplyMM(mvp,0,vp,0,model,0)
            Matrix.invertM(inverse,0,model,0);Matrix.transposeM(normalMatrix,0,inverse,0)
            glUniformMatrix4fv(normalLoc,1,false,normalMatrix,0)
            glUniformMatrix4fv(mvpLoc,1,false,mvp,0);glUniformMatrix4fv(modelLoc,1,false,model,0);glUniform4fv(colorLoc,1,item.color,0)
            val mesh=if(item.round) ball else box
            mesh.data.position(0);glVertexAttribPointer(pos,3,GL_FLOAT,false,24,mesh.data);glEnableVertexAttribArray(pos)
            mesh.data.position(3);glVertexAttribPointer(normal,3,GL_FLOAT,false,24,mesh.data);glEnableVertexAttribArray(normal)
            glDrawArrays(GL_TRIANGLES,0,mesh.count)
        }
        override fun onDrawFrame(gl:GL10?) {
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT);glUseProgram(program)
            val distance=10.6f;val cy=distance*sin(pitch);val r=distance*cos(pitch)
            val ex=r*sin(yaw);val ez=r*cos(yaw)
            glUniform3f(eyeLoc,ex,cy+1f,ez)
            Matrix.setLookAtM(view,0,ex,cy+1f,ez,0f,1.15f,0f,0f,1f,0f);Matrix.multiplyMM(vp,0,projection,0,view,0)
            draw(Item(0f,-.48f,0f,200f,.06f,200f,color(0x102633)))
            draw(Item(0f,-.43f,0f,6.4f,.035f,4.5f,color(0x091A24),round=true))
            architecture.forEach(::draw)
            for(i in 0..5) draw(Item(-1.2f,1.2f+i*.14f,-1.58f,1.28f,.115f,.035f,color(0x9CD8E3),rx=gate*80f))
            val t=SystemClock.uptimeMillis()/1000f
            if (gate > .03f) {
                // Long, curved airflow streaks travel horizontally from the open vent.
                // Their narrow ribbons and lateral motion distinguish them from falling water.
                for (stream in 0 until 7) {
                    val lane = stream / 6f
                    val head = (t * .5f + stream * .23f) % 1.35f
                    val x = -1.72f + (stream % 4) * .32f
                    for (segment in 0 until 8) {
                        val phase = head - segment * .035f
                        if (phase <= 0f || phase >= 1f) continue
                        val z = -1.45f + phase * 2.65f
                        val wave = phase * 8.5f + stream * .8f
                        val y = 1.27f + lane * .5f + sin(wave) * .075f
                        val slope = cos(wave) * .075f * 8.5f / 2.65f
                        val angle = -atan(slope) * 180f / PI.toFloat()
                        val fade = min(phase * 5f, (1f - phase) * 5f).coerceIn(0f, 1f)
                        val tail = (1f - segment / 8f)
                        draw(Item(x, y, z, .014f, .014f, .115f,
                            color(0xDDFEFF, gate * fade * tail * .8f), rx = angle))
                    }
                }
            }
            if(pump) for(x in listOf(-1.4f,0f,1.4f)) for(i in 0..19) {
                val f=(t*.6f+i/20f)%1f;val a=i*2.39996f
                draw(Item(x+cos(a)*f*.55f,1.72f-f*1.5f,.1f+sin(a)*f*.6f,.025f,.065f,.025f,color(0x8EE4FF,.85f),round=true))
            }
            // Transparent glass is sorted back-to-front and never writes the depth buffer.
            val glass=listOf(
                Item(-2.55f,1.15f,0f,.012f,2.25f,3.25f,color(0x7ED6DD,.08f)),
                Item(2.55f,1.15f,0f,.012f,2.25f,3.25f,color(0x7ED6DD,.08f)),
                Item(0f,1.15f,-1.65f,5.05f,2.25f,.012f,color(0x7ED6DD,.06f)),
                Item(-1.275f,2.725f,0f,2.68f,.01f,3.3f,color(0xA5E5ED,.09f),rz=18.43f),
                Item(1.275f,2.725f,0f,2.68f,.01f,3.3f,color(0xA5E5ED,.09f),rz=-18.43f))
            glDepthMask(false)
            glass.sortedByDescending { (it.x-ex).pow(2)+(it.y-cy-1).pow(2)+(it.z-ez).pow(2) }.forEach(::draw)
            glDepthMask(true)
        }
        private class Mesh(values:FloatArray) {
            val count=values.size/6
            val data=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {put(values);position(0)}
            companion object {
                fun cube():Mesh {
                    val out=ArrayList<Float>()
                    val vertices=arrayOf(floatArrayOf(-.5f,-.5f,-.5f),floatArrayOf(.5f,-.5f,-.5f),floatArrayOf(.5f,.5f,-.5f),floatArrayOf(-.5f,.5f,-.5f),floatArrayOf(-.5f,-.5f,.5f),floatArrayOf(.5f,-.5f,.5f),floatArrayOf(.5f,.5f,.5f),floatArrayOf(-.5f,.5f,.5f))
                    val faces=arrayOf(intArrayOf(0,1,2,3),intArrayOf(4,7,6,5),intArrayOf(0,4,5,1),intArrayOf(3,2,6,7),intArrayOf(0,3,7,4),intArrayOf(1,5,6,2))
                    val normals=arrayOf(floatArrayOf(0f,0f,-1f),floatArrayOf(0f,0f,1f),floatArrayOf(0f,-1f,0f),floatArrayOf(0f,1f,0f),floatArrayOf(-1f,0f,0f),floatArrayOf(1f,0f,0f))
                    for((i,f) in faces.withIndex()) for(j in intArrayOf(0,1,2,0,2,3)) {vertices[f[j]].forEach(out::add);normals[i].forEach(out::add)}
                    return Mesh(out.toFloatArray())
                }
                fun sphere():Mesh {
                    val out=ArrayList<Float>()
                    fun vertex(lat:Int,lon:Int) {val a=PI*lat/8;val b=2*PI*lon/12;val x=(sin(a)*cos(b)).toFloat();val y=cos(a).toFloat();val z=(sin(a)*sin(b)).toFloat();out.addAll(listOf(x*.5f,y*.5f,z*.5f,x,y,z))}
                    for(i in 0 until 8) for(j in 0 until 12) {vertex(i,j);vertex(i+1,j);vertex(i+1,j+1);vertex(i,j);vertex(i+1,j+1);vertex(i,j+1)}
                    return Mesh(out.toFloatArray())
                }
            }
        }
    }
}

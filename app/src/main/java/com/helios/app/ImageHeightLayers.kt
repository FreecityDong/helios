package com.helios.app

import kotlin.math.abs
import kotlin.math.sqrt

/** Image regions, not physical heights or estimates of the user's body. */
enum class ImageHeightBand(val startFraction: Float,val endFraction: Float,val label: String) {
    HIGH(0f,1f/3f,"上方"), FRONT(1f/3f,2f/3f,"前方"), GROUND(2f/3f,1f,"下方")
}

/** A measured plane supported by the lower image. Never substitutes for unseen ground. */
data class GroundDepthPlane(val nx: Float,val ny: Float,val nz: Float,val distance: Float,
    val supportRatio: Float) {
    fun residual(x: Int,y: Int,z: Float,camera: HorizontalProjection): Float =
        distance-(nx*camera.lateralMeters(x.toFloat(),z)+ny*camera.verticalMeters(y.toFloat(),z)+nz*z)
}

object GroundDepthPlaneEstimator {
    // Mount height supplied by the user. Used only to avoid treating a bed/table top as floor.
    const val CAMERA_HEIGHT_METERS=1.4f
    fun estimate(bytes: ByteArray,width: Int,height: Int,scale: Float,camera: HorizontalProjection?): GroundDepthPlane? {
        if (camera == null || !camera.matches(width,height) || !camera.verticalAvailable ||
            bytes.size.toLong()<width.toLong()*height*2 || !scale.isFinite() || scale<=0f) return null
        data class Point(val x: Float,val y: Float,val z: Float,val pixelX: Int)
        val points=ArrayList<Point>()
        for (y in (height*0.85f).toInt() until height step 8) for (x in 0 until width step 12) {
            val z=depthAt(bytes,width,x,y,scale) ?: continue
            points.add(Point(camera.lateralMeters(x.toFloat(),z),camera.verticalMeters(y.toFloat(),z),z,x))
        }
        if (points.size<48) return null
        var best: GroundDepthPlane?=null
        var bestSupport=0
        // Deterministic bounded RANSAC; spatial support and mounting height constrain the floor.
        for (iteration in 0 until 96) {
            val a=points[(iteration*17)%points.size]
            val b=points[(iteration*37+points.size/3)%points.size]
            val c=points[(iteration*61+points.size*2/3)%points.size]
            val ux=b.x-a.x;val uy=b.y-a.y;val uz=b.z-a.z
            val vx=c.x-a.x;val vy=c.y-a.y;val vz=c.z-a.z
            var nx=uy*vz-uz*vy;var ny=uz*vx-ux*vz;var nz=ux*vy-uy*vx
            val norm=sqrt(nx*nx+ny*ny+nz*nz)
            if (norm<0.0001f) continue
            nx/=norm;ny/=norm;nz/=norm
            if (ny<0f) { nx=-nx;ny=-ny;nz=-nz }
            if (ny<0.82f) continue
            val d=nx*a.x+ny*a.y+nz*a.z
            if (abs(d-CAMERA_HEIGHT_METERS)>0.25f) continue
            var supported=0;var left=width;var right=0;var minZ=4f;var maxZ=0f
            for (p in points) if (abs(nx*p.x+ny*p.y+nz*p.z-d)<=0.04f) {
                supported++;left=minOf(left,p.pixelX);right=maxOf(right,p.pixelX)
                minZ=minOf(minZ,p.z);maxZ=maxOf(maxZ,p.z)
            }
            if (supported>bestSupport && supported>=32 && supported.toFloat()/points.size>=0.65f &&
                right-left>=width*0.60f && maxZ-minZ>=0.30f) {
                bestSupport=supported
                best=GroundDepthPlane(nx,ny,nz,d,supported.toFloat()/points.size)
            }
        }
        return best
    }
}

/** Conservative ceiling model: a broad measured plane well above the camera. */
object CeilingDepthPlaneEstimator {
    fun estimate(bytes: ByteArray,width: Int,height: Int,scale: Float,camera: HorizontalProjection): GroundDepthPlane? {
        if(!camera.matches(width,height) || !camera.verticalAvailable) return null
        data class Point(val x: Float,val y: Float,val z: Float,val pixelX: Int,val pixelY: Int)
        val points=ArrayList<Point>()
        for(y in 0 until (height*0.15f).toInt() step 8) for(x in 0 until width step 12) {
            val z=depthAt(bytes,width,x,y,scale,6f) ?: continue
            points.add(Point(camera.lateralMeters(x.toFloat(),z),camera.verticalMeters(y.toFloat(),z),z,x,y))
        }
        if(points.size<48) return null
        var best: GroundDepthPlane?=null;var bestSupport=0
        // Furniture and walls can occupy most of the top strip. Fit bounded
        // overlapping windows, but require a broad, two-dimensional plane
        // supported by measured points within its own observed extent.
        val windows=listOf(points,points.filter { it.pixelX in (width*0.2f).toInt()..(width*0.8f).toInt() },
            points.filter { it.pixelX in (width*0.3f).toInt()..(width*0.7f).toInt() })
        for(window in windows) {
            if(window.size<48) continue
            for(iteration in 0 until 96) {
                val a=window[(iteration*17)%window.size];val b=window[(iteration*37+window.size/3)%window.size]
                val c=window[(iteration*61+window.size*2/3)%window.size]
                val ux=b.x-a.x;val uy=b.y-a.y;val uz=b.z-a.z
                val vx=c.x-a.x;val vy=c.y-a.y;val vz=c.z-a.z
                var nx=uy*vz-uz*vy;var ny=uz*vx-ux*vz;var nz=ux*vy-uy*vx
                val norm=sqrt(nx*nx+ny*ny+nz*nz)
                if(norm<0.0001f) continue
                nx/=norm;ny/=norm;nz/=norm
                if(ny<0f) { nx=-nx;ny=-ny;nz=-nz }
                val distance=nx*a.x+ny*a.y+nz*a.z
                if(ny<0.82f || distance !in -4f..-0.60f) continue
                var supported=0;var left=width;var right=0;var top=height;var bottom=0;var minZ=6f;var maxZ=0f
                for(point in points) if(abs(nx*point.x+ny*point.y+nz*point.z-distance)<=0.04f) {
                    supported++;left=minOf(left,point.pixelX);right=maxOf(right,point.pixelX)
                    top=minOf(top,point.pixelY);bottom=maxOf(bottom,point.pixelY)
                    minZ=minOf(minZ,point.z);maxZ=maxOf(maxZ,point.z)
                }
                if(supported<=bestSupport || supported<32 || right-left<width*0.25f ||
                    bottom-top<height*0.06f || maxZ-minZ<0.30f) continue
                val regionCount=points.count { it.pixelX in left..right && it.pixelY in top..bottom }
                val supportRatio=supported.toFloat()/regionCount.coerceAtLeast(1)
                if(supportRatio>=0.65f) {
                    bestSupport=supported;best=GroundDepthPlane(nx,ny,nz,distance,supportRatio)
                }
            }
        }
        return best
    }
}

internal fun depthAt(bytes: ByteArray,width: Int,x: Int,y: Int,scale: Float,maxMeters: Float=4f): Float? {
    val i=(y*width+x)*2
    val raw=(bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8)
    return (raw*scale/1000f).takeIf { raw!=0 && it in 0.25f..maxMeters }
}

data class ForwardLayerSurface(val box: TargetBox,val meters: Float,val band: ImageHeightBand)

class ImageHeightScene(val bytes: ByteArray,val width: Int,val height: Int,val scale: Float,
    val camera: HorizontalProjection) {
    val ground=GroundDepthPlaneEstimator.estimate(bytes,width,height,scale,camera)
    val ceiling=CeilingDepthPlaneEstimator.estimate(bytes,width,height,scale,camera)
    // The recorded ceiling has 4–5.3 cm depth variation. Keep the floor
    // tolerance separate and preserve objects protruding from the ceiling.
    fun ceilingSurface(x: Int,y: Int,z: Float)=ceiling?.residual(x,y,z,camera)?.let { abs(it)<=0.06f } == true
    fun groundHazard(x: Int,y: Int,z: Float): Boolean {
        val residual=ground?.residual(x,y,z,camera) ?: return false
        return residual>=0.08f || residual<=-0.12f
    }
    fun floor(x: Int,y: Int,z: Float)=ground?.residual(x,y,z,camera)?.let { abs(it)<=0.04f } == true
    fun accept(band: ImageHeightBand,x: Int,y: Int,z: Float): Boolean =
        y>=(height*band.startFraction).toInt() && y<(height*band.endFraction).toInt() &&
        camera.inForward(x.toFloat(),z) &&
            !floor(x,y,z) && !ceilingSurface(x,y,z) &&
            (band!=ImageHeightBand.GROUND || ground==null || groundHazard(x,y,z))

    /** Verify the actual measured class surface inside the metre-wide forward region. */
    fun known(box: TargetBox,band: ImageHeightBand): ForwardLayerSurface? {
        val reference=box.distance.meters ?: return null
        if (box.confidence<0.45f || box.distance.validRatio<0.5f || !reference.isFinite()) return null
        val x0=box.left.toInt().coerceIn(0,width-1);val x1=box.right.toInt().coerceIn(0,width-1)
        val y0=maxOf(box.top.toInt(),(height*band.startFraction).toInt()).coerceIn(0,height-1)
        val y1=minOf(box.bottom.toInt(),(height*band.endFraction).toInt()-1).coerceIn(0,height-1)
        if (x1-x0<4 || y1-y0<4) return null
        var nearest: Float?=null
        for (y in y0 until y1-3 step 4) for (x in x0 until x1-3 step 4) {
            val values=ArrayList<Float>(4)
            for (dy in intArrayOf(0,4)) for (dx in intArrayOf(0,4)) {
                val z=depthAt(bytes,width,x+dx,y+dy,scale) ?: continue
                if (abs(z-reference)<=maxOf(0.15f,reference*0.10f) && accept(band,x+dx,y+dy,z)) values.add(z)
            }
            if (values.size==4 && values.max()-values.min()<=0.12f) {
                val z=values.average().toFloat()
                nearest=nearest?.let { minOf(it,z) } ?: z
            }
        }
        return nearest?.let { ForwardLayerSurface(box.copy(distance=DepthEstimate(it,1f,"前方范围连续表面")),it,band) }
    }
    fun unknown(band: ImageHeightBand,maxMeters: Float): ForwardLayerSurface? {
        val point=UnknownObstacleDetector.detect(bytes,width,height,scale,maxMeters,camera,band) { x,y,z -> accept(band,x,y,z) }
            ?: return null
        return ForwardLayerSurface(TargetBox(-1,1f,width*0.38f,height*band.startFraction,
            width*0.62f,height*band.endFraction,DepthEstimate(point.meters,1f,"${band.label}连续深度面")),point.meters,band)
    }
}

#include frex:shaders/api/vertex.glsl
#include frex:shaders/lib/face.glsl
#include frex:shaders/api/player.glsl

// sends noise coordinates from the vertex shader
varying vec2 v_noise_uv;
varying float distance;

void frx_startVertex(inout frx_VertexData data) {
	// 2D noise coordinates are derived from world geometry using a Canvas library function
	v_noise_uv = frx_faceUv(data.vertex.xyz, data.normal);
	data.vertex.x += distance(_cvu_camera_pos, data.vertex.xyz);
}

/*
 * Copyright 2019, 2020 grondag
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package grondag.canvas.buffer.encoding;

import com.google.common.primitives.Doubles;
import grondag.canvas.material.EncodingContext;
import grondag.canvas.material.MaterialState;
import grondag.canvas.material.MaterialVertexFormat;
import grondag.canvas.material.MaterialVertexFormats;
import grondag.fermion.intstream.IntStreamProvider;
import grondag.fermion.intstream.IntStreamProvider.IntStreamImpl;
import it.unimi.dsi.fastutil.Swapper;
import it.unimi.dsi.fastutil.ints.IntComparator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;

import java.nio.IntBuffer;

public class VertexCollectorImpl implements VertexCollector {
	private static final ThreadLocal<QuadSorter> quadSorter = new ThreadLocal<QuadSorter>() {
		@Override
		protected QuadSorter initialValue() {
			return new QuadSorter();
		}
	};
	// TODO: make parameters dynamic based on system specs / config
	private static final IntStreamProvider INT_STREAM_PROVIDER = new IntStreamProvider(0x10000, 16, 4096);
	private final IntStreamImpl data = INT_STREAM_PROVIDER.claim();
	private int integerSize = 0;
	/**
	 * Used for vanilla quads
	 */
	private VertexEncoder defaultEncoder;
	private MaterialState materialState;
	private MaterialVertexFormat format;
	/**
	 * Holds per-quad distance after {@link #sortQuads(double, double, double)} is
	 * called
	 */
	private double[] perQuadDistance;
	/**
	 * Pointer to next sorted quad in sort iteration methods.<br>
	 * After {@link #sortQuads(float, float, float)} is called this will be zero.
	 */
	private int sortReadIndex = 0;
	/**
	 * Cached value of {@link #quadCount()}, set when quads are sorted by distance.
	 */
	private int sortMaxIndex = 0;

	public VertexCollectorImpl() {
	}

	public VertexCollectorImpl prepare(EncodingContext context, MaterialState materialState) {
		defaultEncoder = VertexEncoders.getDefault(context, materialState);
		this.materialState = materialState;
		format = MaterialVertexFormats.get(context, materialState.isTranslucent);
		return this;
	}

	public void clear() {
		integerSize = 0;
		data.reset();
	}

	public int integerSize() {
		return integerSize;
	}

	public int byteSize() {
		return integerSize * 4;
	}

	public boolean isEmpty() {
		return integerSize == 0;
	}

	public MaterialState materialState() {
		return materialState;
	}

	public int vertexCount() {
		return integerSize / format.vertexStrideInts;
	}

	public int quadCount() {
		return vertexCount() / 4;
	}

	@Override
	public VertexCollectorImpl clone() {
		throw new UnsupportedOperationException();
	}

	public void sortQuads(double x, double y, double z) {
		quadSorter.get().doSort(this, x, y, z);
		sortReadIndex = 0;
		sortMaxIndex = quadCount();
	}

	private double getDistanceSq(double x, double y, double z, int integerStride, int vertexIndex) {
		// unpack vertex coordinates
		int i = vertexIndex * integerStride * 4;
		final double x0 = Float.intBitsToFloat(data.get(i));
		final double y0 = Float.intBitsToFloat(data.get(i + 1));
		final double z0 = Float.intBitsToFloat(data.get(i + 2));

		i += integerStride;
		final double x1 = Float.intBitsToFloat(data.get(i));
		final double y1 = Float.intBitsToFloat(data.get(i + 1));
		final double z1 = Float.intBitsToFloat(data.get(i + 2));

		i += integerStride;
		final double x2 = Float.intBitsToFloat(data.get(i));
		final double y2 = Float.intBitsToFloat(data.get(i + 1));
		final double z2 = Float.intBitsToFloat(data.get(i + 2));

		i += integerStride;
		final double x3 = Float.intBitsToFloat(data.get(i));
		final double y3 = Float.intBitsToFloat(data.get(i + 1));
		final double z3 = Float.intBitsToFloat(data.get(i + 2));

		// compute average distance by component
		final double dx = (x0 + x1 + x2 + x3) * 0.25 - x;
		final double dy = (y0 + y1 + y2 + y3) * 0.25 - y;
		final double dz = (z0 + z1 + z2 + z3) * 0.25 - z;

		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * Index of first quad that will be referenced by {@link #unpackUntilDistance(double)}
	 */
	public int sortReadIndex() {
		return sortReadIndex;
	}

	public boolean hasUnpackedSortedQuads() {
		return perQuadDistance != null && sortReadIndex < sortMaxIndex;
	}

	/**
	 * Will return {@link Double#MIN_VALUE} if no unpacked quads remaining.
	 */
	public double firstUnpackedDistance() {
		return hasUnpackedSortedQuads() ? perQuadDistance[sortReadIndex] : Double.MIN_VALUE;
	}

	/**
	 * Returns the number of quads that are more or as distant than the distance
	 * provided and advances the usage pointer so that
	 * {@link #firstUnpackedDistance()} will return the distance to the next quad
	 * after that.
	 * <p>
	 * <p>
	 * (All distances are actually squared distances, to be clear.)
	 */
	public int unpackUntilDistance(double minDistanceSquared) {
		if (!hasUnpackedSortedQuads()) {
			return 0;
		}

		int result = 0;
		final int limit = sortMaxIndex;
		while (sortReadIndex < limit && minDistanceSquared <= perQuadDistance[sortReadIndex]) {
			result++;
			sortReadIndex++;
		}
		return result;
	}

	public int[] saveState(int[] priorState) {
		if (integerSize == 0) {
			return null;
		}

		int[] result = priorState;

		if (result == null || result.length != integerSize) {
			result = new int[integerSize];
		}

		if (integerSize > 0) {
			data.copyTo(0, result, 0, integerSize);
		}

		return result;
	}

	public VertexCollectorImpl loadState(MaterialState state, int[] stateData) {
		if (stateData == null) {
			clear();
			return this;
		}

		materialState = state;
		final int newSize = stateData.length;
		integerSize = 0;

		if (newSize > 0) {
			integerSize = newSize;
			data.copyFrom(0, stateData, 0, newSize);
		}

		return this;
	}

	public void toBuffer(IntBuffer intBuffer) {
		data.copyTo(0, intBuffer, integerSize);
	}

	@Override
	public final void addi(final int i) {
		data.set(integerSize++, i);
	}

	@Override
	public final void addf(final float f) {
		data.set(integerSize++, Float.floatToRawIntBits(f));
	}

	@Override
	public final void addf(final float u, float v) {
		data.set(integerSize++, Float.floatToRawIntBits(u));
		data.set(integerSize++, Float.floatToRawIntBits(v));
	}

	@Override
	public final void addf(final float x, float y, float z) {
		data.set(integerSize++, Float.floatToRawIntBits(x));
		data.set(integerSize++, Float.floatToRawIntBits(y));
		data.set(integerSize++, Float.floatToRawIntBits(z));
	}

	@Override
	public final void addf(final float... fArray) {
		for (final float f : fArray) {
			data.set(integerSize++, Float.floatToRawIntBits(f));
		}
	}

	@Override
	public final void add(int[] appendData, int length) {
		data.copyFrom(integerSize, appendData, 0, length);
		integerSize += length;
	}

	@Override
	public VertexConsumer vertex(double x, double y, double z) {
		assert defaultEncoder != null;
		defaultEncoder.vertex(this, x, y, z);
		return this;
	}

	@Override
	public void vertex(float x, float y, float z, float i, float j, float k, float l, float m, float n, int o, int p, float q, float r, float s) {
		defaultEncoder.vertex(this, x, y, z, i, j, k, l, m, n, o, p, q, r, s);
		next();
	}

	@Override
	public VertexConsumer color(int r, int g, int b, int a) {
		defaultEncoder.color(this, r, g, b, a);
		return this;
	}

	@Override
	public VertexConsumer texture(float u, float v) {
		defaultEncoder.texture(this, u, v);
		return this;
	}

	@Override
	public VertexConsumer overlay(int s, int t) {
		defaultEncoder.overlay(this, s, t);
		return this;
	}

	@Override
	public VertexConsumer light(int s, int t) {
		defaultEncoder.light(this, s, t);
		return this;
	}

	@Override
	public VertexConsumer normal(float x, float y, float z) {
		defaultEncoder.normal(this, x, y, z);
		return this;
	}

	@Override
	public void next() {
		// NOOP
	}

	private static class QuadSorter {
		double[] perQuadDistance = new double[512];
		private final IntComparator comparator = new IntComparator() {
			@Override
			public int compare(int a, int b) {
				return Doubles.compare(perQuadDistance[b], perQuadDistance[a]);
			}
		};
		int[] quadSwap = new int[128];
		IntStreamImpl data;
		int quadIntStride;
		private final Swapper swapper = new Swapper() {
			@Override
			public void swap(int a, int b) {
				final double distSwap = perQuadDistance[a];
				perQuadDistance[a] = perQuadDistance[b];
				perQuadDistance[b] = distSwap;

				data.copyTo(a * quadIntStride, quadSwap, 0, quadIntStride);
				data.copyFromDirect(a * quadIntStride, data, b * quadIntStride, quadIntStride);
				data.copyFrom(b * quadIntStride, quadSwap, 0, quadIntStride);
			}
		};

		private void doSort(VertexCollectorImpl caller, double x, double y, double z) {
			data = caller.data;

			// works because 4 bytes per int
			quadIntStride = caller.format.vertexStrideBytes;
			final int vertexIntStride = quadIntStride / 4;
			final int quadCount = caller.vertexCount() / 4;

			if (perQuadDistance.length < quadCount) {
				perQuadDistance = new double[MathHelper.smallestEncompassingPowerOfTwo(quadCount)];
			}

			if (quadSwap.length < quadIntStride) {
				quadSwap = new int[MathHelper.smallestEncompassingPowerOfTwo(quadIntStride)];
			}

			for (int j = 0; j < quadCount; ++j) {
				perQuadDistance[j] = caller.getDistanceSq(x, y, z, vertexIntStride, j);
			}

			// sort the indexes by distance - farthest first
			it.unimi.dsi.fastutil.Arrays.quickSort(0, quadCount, comparator, swapper);

			if (caller.perQuadDistance == null || caller.perQuadDistance.length < quadCount) {
				caller.perQuadDistance = new double[perQuadDistance.length];
			}

			System.arraycopy(perQuadDistance, 0, caller.perQuadDistance, 0, quadCount);
		}
	}
}

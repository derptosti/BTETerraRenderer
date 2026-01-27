package com.mndk.bteterrarenderer.mcconnector.client.graphics;

import com.mndk.bteterrarenderer.mcconnector.client.graphics.shape.GraphicsQuad;
import com.mndk.bteterrarenderer.mcconnector.client.graphics.shape.GraphicsTriangle;
import com.mndk.bteterrarenderer.mcconnector.client.graphics.vertex.PosTex;
import com.mndk.bteterrarenderer.mcconnector.client.graphics.vertex.PosTexNorm;
import com.mndk.bteterrarenderer.mcconnector.util.math.McCoord;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.TriState;
import net.minecraft.util.Util;
import org.joml.Vector2f;

import java.util.function.BiFunction;

public class BufferBuildersManagerImpl implements BufferBuildersManager {

    /**
     * Minecraft 1.21.11 switched RenderLayer creation to the RenderSetup API.
     */
    private static RenderSetup generateSetup(RenderPipeline pipeline, Identifier texture, boolean translucent) {
        RenderSetup.Builder builder = RenderSetup.builder(pipeline)
                // Sampler name must match what the pipeline declares via withSampler(...)
                .texture("Sampler0", texture)
                .useLightmap()
                .useOverlay()
                .expectedBufferSize(1536)
                .outlineMode(RenderSetup.OutlineMode.AFFECTS_OUTLINE);
        if (translucent) {
            builder.translucent();
        }
        return builder.build();
    }

    private static final BiFunction<VertexFormat.DrawMode, Boolean, RenderPipeline> TRANSLUCENT_PIPELINE = Util.memoize(
            (drawMode, cull) -> RenderPipelines.register(RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                    .withLocation("pipeline/entity_translucent")
                    .withSampler("Sampler0")
                    .withSampler("Sampler2")
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, drawMode)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withCull(cull)
                    .build()
            )
    );

    private static final BiFunction<VertexFormat.DrawMode, Boolean, RenderPipeline> OPAQUE_PIPELINE = Util.memoize(
            (drawMode, cull) -> RenderPipelines.register(RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                    .withLocation("pipeline/entity_opaque")
                    .withSampler("Sampler0")
                    .withSampler("Sampler2")
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, drawMode)
                    .withCull(cull)
                    .build()
            )
    );

    private static final BiFunction<Identifier, Boolean, RenderLayer> QUADS_TRANSLUCENT = Util.memoize(
            (texture, cull) -> {
                RenderPipeline pipeline = TRANSLUCENT_PIPELINE.apply(VertexFormat.DrawMode.QUADS, cull);
                return RenderLayer.of("bteterrarenderer-quads-translucent", generateSetup(pipeline, texture, true));
            }
    );

    private static final BiFunction<Identifier, Boolean, RenderLayer> TRIS_TRANSLUCENT = Util.memoize(
            (texture, cull) -> {
                RenderPipeline pipeline = TRANSLUCENT_PIPELINE.apply(VertexFormat.DrawMode.TRIANGLES, cull);
                return RenderLayer.of("bteterrarenderer-tris-translucent", generateSetup(pipeline, texture, true));
            }
    );

    private static final BiFunction<Identifier, Boolean, RenderLayer> QUADS_OPAQUE = Util.memoize(
            (texture, cull) -> {
                RenderPipeline pipeline = OPAQUE_PIPELINE.apply(VertexFormat.DrawMode.QUADS, cull);
                return RenderLayer.of("bteterrarenderer-quads-opaque", generateSetup(pipeline, texture, false));
            }
    );

    private static final BiFunction<Identifier, Boolean, RenderLayer> TRIS_OPAQUE = Util.memoize(
            (texture, cull) -> {
                RenderPipeline pipeline = OPAQUE_PIPELINE.apply(VertexFormat.DrawMode.TRIANGLES, cull);
                return RenderLayer.of("bteterrarenderer-tris-opaque", generateSetup(pipeline, texture, false));
            }
    );

    @Override
    public BufferBuilderWrapper<GraphicsQuad<PosTex>> begin3dQuad(NativeTextureWrapper texture, float alpha, boolean cull) {
        Identifier id = ((NativeTextureWrapperImpl) texture).delegate;
        RenderLayer renderLayer = QUADS_OPAQUE.apply(id, cull);

        // DrawMode.QUADS
        // VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
        return new QuadBufferBuilderWrapper<>() {
            private MatrixStack.Entry entry;
            private VertexConsumer consumer;
            public void setContext(WorldDrawContextWrapper context) {
                this.entry = ((WorldDrawContextWrapperImpl) context).stack().peek();
                this.consumer = ((WorldDrawContextWrapperImpl) context).provider().getBuffer(renderLayer);
            }
            public void next(PosTex vertex) {
                McCoord tp = this.getTransformer().transform(vertex.pos);
                nextVertex(entry, consumer, tp, vertex.tex, new McCoord(0, 1, 0), alpha);
            }
        };
    }

    @Override
    public BufferBuilderWrapper<GraphicsTriangle<PosTexNorm>> begin3dTri(NativeTextureWrapper texture,
                                                                         float alpha, boolean enableNormal, boolean cull) {
        Identifier id = ((NativeTextureWrapperImpl) texture).delegate;
        RenderLayer renderLayer = TRIS_OPAQUE.apply(id, cull);

        // DrawMode.QUADS
        // VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
        return new TriangleBufferBuilderWrapper<>() {
            private MatrixStack.Entry entry;
            private VertexConsumer consumer;
            public void setContext(WorldDrawContextWrapper context) {
                this.entry = ((WorldDrawContextWrapperImpl) context).stack().peek();
                this.consumer = ((WorldDrawContextWrapperImpl) context).provider().getBuffer(renderLayer);
            }
            public void next(PosTexNorm vertex) {
                PosTexNorm tv = vertex.transform(this.getTransformer());
                nextVertex(entry, consumer, tv.pos, tv.tex, enableNormal ? tv.normal : new McCoord(0, 1, 0), alpha);
            }
        };
    }

    private static void nextVertex(MatrixStack.Entry entry, VertexConsumer consumer,
                                   McCoord pos, Vector2f tex, McCoord normal, float alpha) {
        consumer.vertex(entry, (float) pos.getX(), pos.getY(), (float) pos.getZ())
                .color(1, 1, 1, alpha)
                .texture(tex.x, tex.y)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(0x00F000F0)
                .normal(entry, (float) normal.getX(), normal.getY(), (float) normal.getZ());
    }
}

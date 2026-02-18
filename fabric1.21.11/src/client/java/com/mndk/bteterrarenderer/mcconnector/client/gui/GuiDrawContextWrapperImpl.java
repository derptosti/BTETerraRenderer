package com.mndk.bteterrarenderer.mcconnector.client.gui;

import com.mndk.bteterrarenderer.mcconnector.McConnector;
import com.mndk.bteterrarenderer.mcconnector.client.WindowDimension;
import com.mndk.bteterrarenderer.mcconnector.client.graphics.NativeTextureWrapper;
import com.mndk.bteterrarenderer.mcconnector.client.graphics.NativeTextureWrapperImpl;
import com.mndk.bteterrarenderer.mcconnector.client.graphics.shape.GraphicsQuad;
import com.mndk.bteterrarenderer.mcconnector.client.graphics.vertex.PosXY;
import com.mndk.bteterrarenderer.mcconnector.client.gui.widget.AbstractWidgetCopy;
import com.mndk.bteterrarenderer.mcconnector.client.text.FontWrapper;
import com.mndk.bteterrarenderer.mcconnector.client.text.FontWrapperImpl;
import com.mndk.bteterrarenderer.mcconnector.client.text.StyleWrapper;
import com.mndk.bteterrarenderer.mcconnector.client.text.StyleWrapperImpl;
import com.mndk.bteterrarenderer.mcconnector.util.ResourceLocationWrapper;
import com.mndk.bteterrarenderer.mcconnector.util.ResourceLocationWrapperImpl;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.gui.screen.ButtonTextures;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.text.Style;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@RequiredArgsConstructor
public class GuiDrawContextWrapperImpl extends AbstractGuiDrawContextWrapper {

    private static final Identifier CHECKBOX_SELECTED_HIGHLIGHTED = Identifier.of("widget/checkbox_selected_highlighted");
    private static final Identifier CHECKBOX_SELECTED = Identifier.of("widget/checkbox_selected");
    private static final Identifier CHECKBOX_HIGHLIGHTED = Identifier.of("widget/checkbox_highlighted");
    private static final Identifier CHECKBOX = Identifier.of("widget/checkbox");

    private static final ButtonTextures BUTTON_TEXTURES = new ButtonTextures(
            Identifier.of("widget/button"),
            Identifier.of("widget/button_disabled"),
            Identifier.of("widget/button_highlighted")
    );

    private int scissorDepth;

    @Nonnull public final DrawContext delegate;

    public void translate(float x, float y, float z) {
        // GUI matrices are 2D in this version
        delegate.getMatrices().translate(x, y);
    }

    public void pushMatrix() {
        delegate.getMatrices().pushMatrix();
    }

    public void popMatrix() {
        delegate.getMatrices().popMatrix();
    }

    @Override
    protected boolean usesNativeScissorStack() {
        return true;
    }

    protected int[] getAbsoluteScissorDimension(int relX, int relY, int relWidth, int relHeight) {
        WindowDimension window = McConnector.client().getWindowSize();
        if (window.getScaledWidth() == 0 || window.getScaledHeight() == 0) {
            return new int[] { 0, 0, 0, 0 };
        }

        // DrawContext.enableScissor applies the current matrix transform.
        return new int[] { relX, relY, relWidth, relHeight };
    }

    protected void glEnableScissor(int x, int y, int width, int height) {
        // DrawContext scissor uses x1,y1,x2,y2
        delegate.enableScissor(x, y, x + width, y + height);
        scissorDepth++;
    }

    protected void glDisableScissor() {
        if (scissorDepth > 0) {
            delegate.disableScissor();
            scissorDepth--;
        }
    }

    // Vertex system changed in 1.21.6
    public record QuadRenderState(
            RenderPipeline pipeline,
            TextureSetup textureSetup,
            Matrix3x2fc pose,
            GraphicsQuad<PosXY> quad,
            int color,
            @Nullable ScreenRect scissorArea,
            @Nullable ScreenRect bounds
    ) implements SimpleGuiElementRenderState {
        public QuadRenderState(
                RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose, GraphicsQuad<PosXY> quad,
                int color, @Nullable ScreenRect scissorArea
        ) {
            this(pipeline, textureSetup, pose, quad, color, scissorArea, createBounds(quad, pose, scissorArea));
        }

        // Depth removed in 1.21.9
        public void setupVertices(VertexConsumer vertices/*, float depth*/) {
            vertices.vertex(pose(), quad.v0.x, quad.v0.y/*, depth*/).color(color);
            vertices.vertex(pose(), quad.v1.x, quad.v1.y/*, depth*/).color(color);
            vertices.vertex(pose(), quad.v2.x, quad.v2.y/*, depth*/).color(color);
            vertices.vertex(pose(), quad.v3.x, quad.v3.y/*, depth*/).color(color);
        }

        private static ScreenRect createBounds(GraphicsQuad<PosXY> quad, Matrix3x2fc pose, @Nullable ScreenRect scissorArea) {
            int l = MathHelper.floor(Math.min(Math.min(quad.v0.x, quad.v1.x), Math.min(quad.v2.x, quad.v3.x)));
            int t = MathHelper.floor(Math.min(Math.min(quad.v0.y, quad.v1.y), Math.min(quad.v2.y, quad.v3.y)));
            int r = MathHelper.ceil(Math.max(Math.max(quad.v0.x, quad.v1.x), Math.max(quad.v2.x, quad.v3.x)));
            int b = MathHelper.ceil(Math.max(Math.max(quad.v0.y, quad.v1.y), Math.max(quad.v2.y, quad.v3.y)));
            ScreenRect screenRect = new ScreenRect(l, t, r - l, b - t).transformEachVertex(pose);
            return scissorArea != null ? scissorArea.intersection(screenRect) : screenRect;
        }
    }

    public void fillQuad(GraphicsQuad<PosXY> quad, int color, float z) {
        delegate.state.addSimpleElement(new QuadRenderState(
                RenderPipelines.GUI, TextureSetup.empty(), new Matrix3x2f(delegate.getMatrices()), quad, color,
                delegate.scissorStack.peekLast()));
    }

    public void drawButton(int x, int y, int width, int height, AbstractWidgetCopy.HoverState hoverState) {
        boolean enabled = hoverState != AbstractWidgetCopy.HoverState.DISABLED;
        boolean focused = hoverState == AbstractWidgetCopy.HoverState.MOUSE_OVER;
        Identifier buttonTexture = BUTTON_TEXTURES.get(enabled, focused);

        delegate.drawGuiTexture(RenderPipelines.GUI_TEXTURED, buttonTexture, x, y, width, height);
    }

    public void drawCheckBox(int x, int y, int width, int height, boolean focused, boolean checked) {
        Identifier identifier = checked
                ? (focused ? CHECKBOX_SELECTED_HIGHLIGHTED : CHECKBOX_SELECTED)
                : (focused ? CHECKBOX_HIGHLIGHTED : CHECKBOX);

        delegate.drawGuiTexture(RenderPipelines.GUI_TEXTURED, identifier, x, y, width, height, ColorHelper.getWhite(1));
    }

    public void drawTextHighlight(int startX, int startY, int endX, int endY) {
        delegate.fill(startX, startY, endX, endY, 0xff0000ff);
    }

    public void drawImage(ResourceLocationWrapper res, int x, int y, int w, int h,
                          float u1, float u2, float v1, float v2) {
        Identifier texture = ((ResourceLocationWrapperImpl) res).delegate();

        delegate.drawTexturedQuad(texture, x, y, x + w, y + h, u1, u2, v1, v2);
    }

    public void drawWholeNativeImage(@Nonnull NativeTextureWrapper allocatedTextureObject, int x, int y, int w, int h) {
        Identifier texture = ((NativeTextureWrapperImpl) allocatedTextureObject).delegate;

        delegate.drawTexturedQuad(texture, x, y, x + w, y + h, 0, 1, 0, 1);
    }

    public void drawHoverEvent(StyleWrapper styleWrapper, int x, int y) {
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        Style style = ((StyleWrapperImpl) styleWrapper).delegate();
        delegate.drawHoverEvent(textRenderer, style, x, y);
    }

    public int drawTextWithShadow(FontWrapper fontWrapper, String string, float x, float y, int color) {
        TextRenderer textRenderer = ((FontWrapperImpl) fontWrapper).delegate;
        delegate.drawTextWithShadow(textRenderer, string, (int) x, (int) y, color);

        return (int) x + textRenderer.getWidth(string);
    }
}

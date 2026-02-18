package com.mndk.bteterrarenderer.mcconnector.client.gui.screen;

import com.mndk.bteterrarenderer.mcconnector.client.gui.GuiDrawContextWrapperImpl;
import com.mndk.bteterrarenderer.mcconnector.client.input.InputKey;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import javax.annotation.Nonnull;

public class AbstractGuiScreenImpl extends Screen {

    public final AbstractGuiScreenCopy delegate;

    public AbstractGuiScreenImpl(@Nonnull AbstractGuiScreenCopy delegate) {
        super(Text.empty());
        this.delegate = delegate;
    }

    @Override
    protected void init() {
        delegate.initGui(this.width, this.height);
    }

    /**
     * 1.21.x: Screen#resize signature is now resize(int,int)
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        delegate.setScreenSize(width, height);
    }

    @Override
    public void tick() {
        delegate.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        delegate.drawScreen(new GuiDrawContextWrapperImpl(context), mouseX, mouseY, delta);
    }

    /**
     * 1.21.6: renderBackground() is no longer in render()
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
    }

    /**
     * 1.21.x: ParentElement#mouseClicked now takes (Click, boolean)
     */
    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        // Call vanilla behavior first (keeps button handling consistent)
        boolean superResult = super.mouseClicked(click, doubleClick);

        double x = click.x();
        double y = click.y();
        int button = click.button();

        boolean delegateResult = delegate.mousePressed(x, y, button);
        return superResult || delegateResult;
    }

    /**
     * 1.21.x: ParentElement#mouseReleased now takes (Click)
     */
    @Override
    public boolean mouseReleased(Click click) {
        boolean superResult = super.mouseReleased(click);

        double x = click.x();
        double y = click.y();
        int button = click.button();

        boolean delegateResult = delegate.mouseReleased(x, y, button);
        return superResult || delegateResult;
    }

    /**
     * 1.21.x: ParentElement#mouseDragged now takes (Click, double, double)
     * The extra doubles are typically drag deltas.
     */
    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        boolean superResult = super.mouseDragged(click, deltaX, deltaY);

        double x = click.x();
        double y = click.y();
        int button = click.button();

        // Your delegate expects (mouseX, mouseY, button, startX, startY)
        // We approximate startX/startY from current - delta
        boolean delegateResult = delegate.mouseDragged(x, y, button, x - deltaX, y - deltaY);
        return superResult || delegateResult;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        boolean superResult = super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        boolean delegateResult = delegate.mouseScrolled(mouseX, mouseY, verticalAmount);
        return superResult || delegateResult;
    }

    /**
     * 1.21.x: Screen#keyPressed now takes (KeyInput)
     */
    @Override
    public boolean keyPressed(KeyInput keyInput) {
        boolean superResult = super.keyPressed(keyInput);

        int keyCode = keyInput.key();
        int scanCode = keyInput.scancode();
        int modifiers = keyInput.modifiers();

        boolean delegateResult = delegate.keyPressed(InputKey.fromGlfwKeyCode(keyCode), scanCode, modifiers);
        return superResult || delegateResult;
    }

    /**
     * 1.21.x: ParentElement#charTyped now takes (CharInput)
     */
    @Override
    public boolean charTyped(CharInput charInput) {
        boolean superResult = super.charTyped(charInput);

        int modifiers = charInput.modifiers();
        int codepoint = charInput.codepoint();
        char chr = codepoint > 0 ? Character.toChars(codepoint)[0] : 0;

        boolean delegateResult = delegate.charTyped(chr, modifiers);
        return superResult || delegateResult;
    }

    @Override
    public void removed() {
        delegate.onRemoved();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return delegate.doesScreenPauseGame();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return delegate.shouldCloseOnEsc();
    }

}

package com.ncorti.slidetoact

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.TypedArray
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.TextViewCompat
import com.ncorti.slidetoact.SlideToActIconUtil.createIconAnimator
import com.ncorti.slidetoact.SlideToActIconUtil.loadIconCompat
import com.ncorti.slidetoact.SlideToActIconUtil.stopIconAnimation
import com.ncorti.slidetoact.SlideToActIconUtil.tintIconCompat

/**
 *  Class representing the custom view, SlideToActView.
 *
 *  SlideToActView is an elegant material designed slider, that enrich your app
 *  with a "Slide-to-unlock" like widget.
 */
class SlideToActView @JvmOverloads constructor(
        context: Context,
        xmlAttrs: AttributeSet? = null,
        defStyleAttr: Int = R.attr.slideToActViewStyle
) : View(context, xmlAttrs, defStyleAttr) {

    companion object {
        const val TAG = "SlideToActView"
    }

    /* -------------------- LAYOUT BOUNDS -------------------- */

    private var mDesiredSliderHeightDp: Float = 72F
    private var mDesiredSliderWidthDp: Float = 280F
    private var mDesiredSliderHeight: Int = 0
    private var mDesiredSliderWidth: Int = 0

    /* -------------------- MEMBERS -------------------- */

    /** Height of the drawing area */
    private var mAreaHeight: Int = 0

    /** Width of the drawing area */
    private var mAreaWidth: Int = 0

    /** Actual Width of the drawing area, used for animations */
    private var mActualAreaWidth: Int = 0

    /** Border Radius, default to mAreaHeight/2, -1 when not initialized */
    private var mBorderRadius: Int = -1

    /** Margin of the cursor from the outer area */
    private var mActualAreaMargin: Int
    private val mOriginAreaMargin: Int

    /** Enable default icon tint for slider **/
    var enableSliderIconTint: Boolean = true

    /** Enable default icon tint for completion **/
    var enableCompleteIconTint: Boolean = true

    /** Text message */
    var text: CharSequence = ""
        set(value) {
            field = value
            mTextView.text = value
            mTextPaint.set(mTextView.paint)
            invalidate()
        }

    /** Typeface for the text field */
    var typeFace = Typeface.NORMAL
        set(value) {
            field = value
            mTextView.typeface = Typeface.create("sans-serif-light", value)
            mTextPaint.set(mTextView.paint)
            invalidate()
        }

    /** Text Appearance used to fully customize the font */
    @StyleRes
    var textAppearance: Int = 0
        set(value) {
            field = value
            if (value != 0) {
                TextViewCompat.setTextAppearance(mTextView, value)
                mTextPaint.set(mTextView.paint)
                mTextPaint.color = mTextView.currentTextColor
            }
        }

    /** Outer color used by the slider (primary)*/
    @ColorInt
    var outerColor: Int = 0
        set(value) {
            field = value
            mOuterPaint.color = value
            invalidate()
        }

    /** Inner color used by the slider (secondary, icon and border) */
    @ColorInt
    var innerColor: Int = 0
        set(value) {
            field = value
            mInnerPaint.color = value
            invalidate()
        }

    /** Stroke for inner borders **/
    var innerStroke: Stroke? = null
        set(value) {
            field = value
            value?.also {
                mInnerStrokePaint.strokeWidth = it.width
                mInnerStrokePaint.color = it.color
            }
            invalidate()
        }

    /** Stroke for outer borders **/
    var outerStroke: Stroke? = null
        set(value) {
            field = value
            value?.also {
                mOuterStrokePaint.strokeWidth = it.width
                mOuterStrokePaint.color = it.color
            }
            invalidate()
        }

    /** Duration of the complete and reset animation (in milliseconds). */
    var animDuration: Long = 300

    /** Duration of vibration after bumping to the end point */
    var bumpVibration: Long = 0L

    @ColorInt
    var textColor: Int = 0
        set(value) {
            field = value
            mTextView.setTextColor(value)
            mTextPaint.color = textColor
            invalidate()
        }

    /** Custom Icon color */
    @ColorInt
    var iconColor: Int = 0
        set(value) {
            field = value
            if (value != 0)
                DrawableCompat.setTint(mDrawableArrow, value)
            invalidate()
        }

    /** Custom Slider Icon */
    @DrawableRes
    var sliderIcon: Int = R.drawable.slidetoact_ic_arrow
        set(value) {
            field = value
            if (field != 0) {
                ResourcesCompat.getDrawable(context.resources, value, context.theme)?.let {
                    mDrawableArrow = it
                    if (iconColor != 0)
                        DrawableCompat.setTint(it, iconColor)
                }
                invalidate()
            }
        }

    /** Slider cursor position (between 0 and (`mAreaWidth - mAreaHeight)) */
    private var mPosition: Int = 0
        set(value) {
            field = value
            if (mAreaWidth - mAreaHeight == 0) {
                // Avoid 0 division
                mPositionPerc = 0f
                mPositionPercInv = 1f
                return
            }
            mPositionPerc = value.toFloat() / (mAreaWidth - mAreaHeight).toFloat()
            mPositionPercInv = 1 - value.toFloat() / (mAreaWidth - mAreaHeight).toFloat()
            mEffectivePosition = mPosition
        }

    /** Slider cursor effective position. This is used to handle the `reversed` scenario. */
    private var mEffectivePosition: Int = 0
        set(value) {
            field = if (isReversed) (mAreaWidth - mAreaHeight) - value else value
        }

    /** Positioning of text */
    private var mTextYPosition = -1f
    private var mTextXPosition = -1f

    /** Private size for the text message */
    private var mTextSize: Int = 0
        set(value) {
            field = value
            mTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mTextSize.toFloat())
            mTextPaint.set(mTextView.paint)
        }

    /** Slider cursor position in percentage (between 0f and 1f) */
    private var mPositionPerc: Float = 0f

    /** 1/mPositionPerc */
    private var mPositionPercInv: Float = 1f

    /* -------------------- ICONS -------------------- */

    private val mIconMargin: Int

    /** Margin for Arrow Icon */
    private var mArrowMargin: Int

    /** Current angle for Arrow Icon */
    private var mArrowAngle: Float = 0f

    /** Margin for Tick Icon */
    private var mTickMargin: Int

    /** Arrow drawable */
    private lateinit var mDrawableArrow: Drawable

    /** Tick drawable, if is an AnimatedVectorDrawable it will be animated */
    private var mDrawableTick: Drawable
    private var mFlagDrawTick: Boolean = false

    @DrawableRes
    var completeIcon: Int = 0
        set(value) {
            field = value
            if (field != 0) {
                mDrawableTick = loadIconCompat(context, value)
                invalidate()
            }
        }

    /* -------------------- PAINT & DRAW -------------------- */
    /** Paint used for outer elements */
    private val mOuterPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Paint used for inner elements */
    private val mInnerPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Paint stroke used for inner elements */
    private val mInnerStrokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** Paint stroke used for outer elements */
    private val mOuterStrokePaint: Paint = Paint(mInnerStrokePaint)

    /** Paint used for text elements */
    private var mTextPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** TextView used for text elements */
    private var mTextView: TextView

    /** Inner rectangle (used for arrow rotation) */
    private var mInnerRect: RectF

    /** Inner stroke rectangle (used for arrow rotation) */
    private var mInnerStrokeRect: RectF


    /** Outer rectangle (used for area drawing) */
    private var mOuterRect: RectF

    /** Outer stroke rectangle (used for area drawing) */
    private var mOuterStrokeRect: RectF


    /** Grace value, when mPositionPerc > mGraceValue slider will perform the 'complete' operations */
    var graceValue: Float = 0.8F

    /** Last X coordinate for the touch event */
    private var mLastX: Float = 0F

    /** Flag to understand if user is moving the slider cursor */
    private var mFlagMoving: Boolean = false

    /** Private flag to check if the slide gesture have been completed */
    private var mIsCompleted = false

    /** Public flag to lock the slider */
    var isLocked = false

    /** Public flag to reverse the slider by 180 degree */
    var isReversed = false
        set(value) {
            field = value
            // We reassign the position field to trigger the re-computation of the effective position.
            mPosition = mPosition
            invalidate()
        }

    /** Public flag to lock the rotation icon */
    var isRotateIcon = true

    /** Public flag to enable complete animation */
    var isAnimateCompletion = true

    /** Public Slide event listeners */
    var onSlideToActAnimationEventListener: OnSlideToActAnimationEventListener? = null
    var onSlideCompleteListener: OnSlideCompleteListener? = null
    var onSlideResetListener: OnSlideResetListener? = null
    var onSlideUserFailedListener: OnSlideUserFailedListener? = null

    init {
        val actualOuterColor: Int
        val actualInnerColor: Int
        val actualTextColor: Int
        val actualIconColor: Int
        val actualStrokeInnerColor: Int
        val actualStrokeInnerWidth: Float
        val actualStrokeOuterColor: Int
        val actualStrokeOuterWidth: Float
        val actualCompleteDrawable: Int

        val defaultStrokeWidth: Float = resources.getDimension(R.dimen.slidetoact_default_stroke_width)

        mTextView = TextView(context)
        mTextPaint = mTextView.paint

        val attrs: TypedArray = context.theme.obtainStyledAttributes(
                xmlAttrs,
                R.styleable.SlideToActView,
                defStyleAttr,
                R.style.SlideToActView
        )
        try {
            mDesiredSliderHeight = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    mDesiredSliderHeightDp,
                    resources.displayMetrics
            ).toInt()
            mDesiredSliderWidth = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    mDesiredSliderWidthDp,
                    resources.displayMetrics
            ).toInt()

            val defaultOuter = ContextCompat.getColor(
                    this.context,
                    R.color.slidetoact_defaultAccent
            )
            val defaultWhite = ContextCompat.getColor(
                    this.context,
                    R.color.slidetoact_white
            )

            with(attrs) {
                mDesiredSliderHeight = getDimensionPixelSize(
                        R.styleable.SlideToActView_slider_height,
                        mDesiredSliderHeight
                )
                mBorderRadius = getDimensionPixelSize(R.styleable.SlideToActView_border_radius, -1)

                actualOuterColor = getColor(R.styleable.SlideToActView_outer_color, defaultOuter)
                actualInnerColor = getColor(R.styleable.SlideToActView_inner_color, defaultWhite)

                actualStrokeInnerColor = getColor(R.styleable.SlideToActView_inner_stroke_color, -1)
                actualStrokeOuterColor = getColor(R.styleable.SlideToActView_outer_stroke_color, -1)

                actualStrokeInnerWidth = getDimension(R.styleable.SlideToActView_inner_stroke_width, defaultStrokeWidth)
                actualStrokeOuterWidth = getDimension(R.styleable.SlideToActView_outer_stroke_width, defaultStrokeWidth)

                // For text color, check if the `text_color` is set.
                // if not check if the `outer_color` is set.
                // if not, default to white.
                actualTextColor = when {
                    hasValue(R.styleable.SlideToActView_text_color) ->
                        getColor(R.styleable.SlideToActView_text_color, defaultWhite)
                    hasValue(R.styleable.SlideToActView_inner_color) -> actualInnerColor
                    else -> defaultWhite
                }

                text = getString(R.styleable.SlideToActView_text) ?: ""
                typeFace = getInt(R.styleable.SlideToActView_text_style, 0)
                mTextSize = getDimensionPixelSize(
                        R.styleable.SlideToActView_text_size,
                        resources.getDimensionPixelSize(R.dimen.slidetoact_default_text_size)
                )
                textColor = actualTextColor

                // TextAppearance is the last as will have precedence over everything text related.
                textAppearance = getResourceId(R.styleable.SlideToActView_text_appearance, 0)

                isLocked = getBoolean(R.styleable.SlideToActView_slider_locked, false)
                isReversed = getBoolean(R.styleable.SlideToActView_slider_reversed, false)
                isRotateIcon = getBoolean(R.styleable.SlideToActView_rotate_icon, true)
                isAnimateCompletion = getBoolean(
                        R.styleable.SlideToActView_animate_completion,
                        true
                )

                graceValue = getFloat(
                        R.styleable.SlideToActView_grace_value_percent,
                        graceValue
                )

                animDuration = getInteger(
                        R.styleable.SlideToActView_animation_duration,
                        300
                ).toLong()
                bumpVibration = getInt(
                        R.styleable.SlideToActView_bump_vibration,
                        0
                ).toLong()

                mOriginAreaMargin = getDimensionPixelSize(
                        R.styleable.SlideToActView_area_margin,
                        resources.getDimensionPixelSize(R.dimen.slidetoact_default_area_margin)
                )
                mActualAreaMargin = mOriginAreaMargin

                enableSliderIconTint = getBoolean(
                        R.styleable.SlideToActView_enable_tint_slider_icon,
                        true
                )

                enableCompleteIconTint = getBoolean(
                        R.styleable.SlideToActView_enable_tint_complete_icon,
                        true
                )

                sliderIcon = getResourceId(
                        R.styleable.SlideToActView_slider_icon,
                        R.drawable.slidetoact_ic_arrow
                )

                // For icon color. check if the `slide_icon_color` is set.
                // if not check if the `outer_color` is set.
                // if not, default to defaultOuter.
                actualIconColor = when {
                    !enableSliderIconTint -> 0
                    hasValue(R.styleable.SlideToActView_slider_icon_color) ->
                        getColor(R.styleable.SlideToActView_slider_icon_color, defaultOuter)
                    hasValue(R.styleable.SlideToActView_outer_color) -> actualOuterColor
                    else -> defaultOuter
                }
                actualCompleteDrawable = getResourceId(
                        R.styleable.SlideToActView_complete_icon,
                        R.drawable.slidetoact_animated_ic_check
                )

                mIconMargin = getDimensionPixelSize(
                        R.styleable.SlideToActView_icon_margin,
                        resources.getDimensionPixelSize(R.dimen.slidetoact_default_icon_margin)
                )

                mArrowMargin = mIconMargin
                mTickMargin = mIconMargin
            }
        } finally {
            attrs.recycle()
        }

        mInnerRect = RectF(
                (mActualAreaMargin + mEffectivePosition).toFloat(),
                mActualAreaMargin.toFloat(),
                (mAreaHeight + mEffectivePosition).toFloat() - mActualAreaMargin.toFloat(),
                mAreaHeight.toFloat() - mActualAreaMargin.toFloat()
        )

        mInnerStrokeRect = RectF(
                mInnerRect.left + mInnerStrokePaint.strokeWidth / 2,
                mInnerRect.top + mInnerStrokePaint.strokeWidth / 2,
                mInnerRect.right - mInnerStrokePaint.strokeWidth / 2,
                mInnerRect.bottom - mInnerStrokePaint.strokeWidth / 2
        )

        mOuterRect = RectF(
                mActualAreaWidth.toFloat(),
                0f,
                mAreaWidth.toFloat() - mActualAreaWidth.toFloat(),
                mAreaHeight.toFloat()
        )

        mOuterStrokeRect = RectF(
                mOuterRect.left + mOuterStrokePaint.strokeWidth / 2,
                mOuterRect.top + mOuterStrokePaint.strokeWidth / 2,
                mOuterRect.right - mOuterStrokePaint.strokeWidth / 2,
                mOuterRect.bottom - mOuterStrokePaint.strokeWidth / 2
        )

        mDrawableTick = loadIconCompat(context, actualCompleteDrawable)

        mTextPaint.textAlign = Paint.Align.CENTER

        outerColor = actualOuterColor
        innerColor = actualInnerColor
        iconColor = actualIconColor


        if (actualStrokeInnerColor != -1)
            innerStroke = Stroke(actualStrokeInnerWidth, actualStrokeInnerColor)

        if (actualStrokeOuterColor != -1)
            outerStroke = Stroke(actualStrokeOuterWidth, actualStrokeOuterColor)

        // This outline provider force removal of shadow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outlineProvider = SlideToActOutlineProvider()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val width: Int

        width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> Math.min(mDesiredSliderWidth, widthSize)
            MeasureSpec.UNSPECIFIED -> mDesiredSliderWidth
            else -> mDesiredSliderWidth
        }
        setMeasuredDimension(width, mDesiredSliderHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        mAreaWidth = w
        mAreaHeight = h
        if (mBorderRadius == -1) {
            // Round if not set up
            mBorderRadius = h / 2
        }

        // Text horizontal/vertical positioning (both centered)
        mTextXPosition = mAreaWidth.toFloat() / 2
        mTextYPosition = (mAreaHeight.toFloat() / 2) -
                (mTextPaint.descent() + mTextPaint.ascent()) / 2

        // Make sure the position is recomputed.
        mPosition = 0
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (canvas == null) return

        // Outer area
        mOuterRect.set(
                mActualAreaWidth.toFloat(),
                0f,
                mAreaWidth.toFloat() - mActualAreaWidth.toFloat(),
                mAreaHeight.toFloat()
        )
        canvas.drawRoundRect(
                mOuterRect,
                mBorderRadius.toFloat(),
                mBorderRadius.toFloat(),
                mOuterPaint
        )

        outerStroke?.also {
            mOuterStrokeRect.set(
                    mOuterRect.left + it.width / 2,
                    mOuterRect.top + it.width / 2,
                    mOuterRect.right - it.width / 2,
                    mOuterRect.bottom - it.width / 2,
            )
            val outerRadius = mBorderRadius.toFloat()
            canvas.drawRoundRect(
                    mOuterStrokeRect,
                    outerRadius,
                    outerRadius,
                    mOuterStrokePaint
            )
        }

        // Text alpha
        mTextPaint.alpha = (255 * mPositionPercInv).toInt()
        // Checking if the TextView has a Transformation method applied (e.g. AllCaps).
        val textToDraw = mTextView.transformationMethod?.getTransformation(text, mTextView) ?: text
        canvas.drawText(
                textToDraw,
                0,
                textToDraw.length,
                mTextXPosition,
                mTextYPosition,
                mTextPaint
        )

        // Inner Cursor
        // ratio is used to compute the proper border radius for the inner rect (see #8).
        val ratio = (mAreaHeight - 2 * mActualAreaMargin).toFloat() / mAreaHeight.toFloat()
        mInnerRect.set(
                (mActualAreaMargin + mEffectivePosition).toFloat(),
                mActualAreaMargin.toFloat(),
                (mAreaHeight + mEffectivePosition).toFloat() - mActualAreaMargin.toFloat(),
                mAreaHeight.toFloat() - mActualAreaMargin.toFloat()
        )
        canvas.drawRoundRect(
                mInnerRect,
                mBorderRadius.toFloat() * ratio,
                mBorderRadius.toFloat() * ratio,
                mInnerPaint
        )

        innerStroke?.also {
            val innerRadius = (mBorderRadius.toFloat() * ratio)
            mInnerStrokeRect.set(
                    mInnerRect.left + it.width / 2,
                    mInnerRect.top + it.width / 2,
                    mInnerRect.right - it.width / 2,
                    mInnerRect.bottom - it.width / 2,
            )
            canvas.drawRoundRect(
                    mInnerStrokeRect,
                    innerRadius,
                    innerRadius,
                    mInnerStrokePaint
            )
        }

        // Arrow angle
        // We compute the rotation of the arrow and we apply .rotate transformation on the canvas.
        canvas.save()
        if (isReversed) {
            canvas.scale(-1F, 1F, mInnerRect.centerX(), mInnerRect.centerY())
        }
        if (isRotateIcon) {
            mArrowAngle = -180 * mPositionPerc
            canvas.rotate(mArrowAngle, mInnerRect.centerX(), mInnerRect.centerY())
        }

        createCropBounds(mDrawableArrow,mInnerRect,mArrowMargin)

        if (mDrawableArrow.bounds.left <= mDrawableArrow.bounds.right &&
                mDrawableArrow.bounds.top <= mDrawableArrow.bounds.bottom
        ) {
            mDrawableArrow.draw(canvas)
        }

        canvas.restore()

        // Tick drawing
        mDrawableTick.setBounds(
                mActualAreaWidth + mTickMargin,
                mTickMargin,
                mAreaWidth - mTickMargin - mActualAreaWidth,
                mAreaHeight - mTickMargin
        )

        if (enableCompleteIconTint)
            tintIconCompat(mDrawableTick, innerColor)

        if (mFlagDrawTick) {
            mDrawableTick.draw(canvas)
        }
    }

    private fun createCropBounds(drawable: Drawable,rect:RectF,margin:Int) {
        val proportion = drawable.intrinsicWidth / drawable.intrinsicHeight.toFloat()
        val width = rect.right.toInt() - margin * 2 - rect.left.toInt()
        val height = rect.bottom.toInt() - margin * 2 - rect.top.toInt()
        val proportionWidth: Int
        val proportionHeight: Int

        if (proportion >= 1) {
            proportionHeight = (width * 1 / proportion).toInt()
            proportionWidth = width
        } else {
            proportionHeight = height
            proportionWidth = (height * proportion).toInt()
        }

        val left = rect.left.toInt() + margin + width / 2 - proportionWidth / 2
        val top = rect.top.toInt() + margin + height / 2 - proportionHeight / 2
        val right = rect.left.toInt() + margin + width / 2 + proportionWidth / 2
        val bottom = rect.top.toInt() + margin + height / 2 + proportionHeight / 2
        drawable.setBounds(
                left,
                top,
                right,
                bottom
        )
    }

    // Intentionally override `performClick` to do not lose accessibility support.
    @Suppress("RedundantOverride")
    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && event.action == MotionEvent.ACTION_DOWN) {
            // Calling performClick on every ACTION_DOWN so OnClickListener is triggered properly.
            performClick()
        }
        if (event != null && isEnabled) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (checkInsideButton(event.x, event.y)) {
                        mFlagMoving = true
                        mLastX = event.x
                        parent.requestDisallowInterceptTouchEvent(true)
                    } else {
                        // Clicking outside the area -> User failed, notify the listener.
                        onSlideUserFailedListener?.onSlideFailed(this, true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    parent.requestDisallowInterceptTouchEvent(false)
                    if ((mPosition > 0 && isLocked) ||
                            (mPosition > 0 && mPositionPerc < graceValue)
                    ) {
                        // Check for grace value
                        val positionAnimator = ValueAnimator.ofInt(mPosition, 0)
                        positionAnimator.duration = animDuration
                        positionAnimator.addUpdateListener {
                            mPosition = it.animatedValue as Int
                            invalidate()
                        }
                        positionAnimator.start()
                    } else if (mPosition > 0 && mPositionPerc >= graceValue) {
                        isEnabled = false // Fully disable touch events
                        startAnimationComplete()
                    } else if (mFlagMoving && mPosition == 0) {
                        // mFlagMoving == true means user successfully grabbed the slider,
                        // but mPosition == 0 means that the slider is released at the beginning
                        // so either a Tap or the user slided back.
                        onSlideUserFailedListener?.onSlideFailed(this, false)
                    }
                    mFlagMoving = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mFlagMoving) {
                        // True if the cursor was not at the end position before this event
                        val wasIncomplete = mPositionPerc < 1f

                        val diffX = event.x - mLastX
                        mLastX = event.x
                        increasePosition(diffX.toInt())
                        invalidate()

                        // If this event brought the cursor to the end position, we can vibrate
                        if (bumpVibration > 0 && wasIncomplete && mPositionPerc == 1f) {
                            handleVibration()
                        }
                    }
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * Private method to check if user has touched the slider cursor
     * @param x The x coordinate of the touch event
     * @param y The y coordinate of the touch event
     * @return A boolean that informs if user has pressed or not
     */
    private fun checkInsideButton(x: Float, y: Float): Boolean {
        return (
                0 < y &&
                        y < mAreaHeight &&
                        mEffectivePosition < x &&
                        x < (mAreaHeight + mEffectivePosition)
                )
    }

    /**
     * Private method for increasing/decreasing the position
     * Ensure that position never exits from its range [0, (mAreaWidth - mAreaHeight)].
     *
     * Please note that the increment is inverted in case of a reversed slider.
     *
     * @param inc Increment to be performed (negative if it's a decrement)
     */
    private fun increasePosition(inc: Int) {
        mPosition = if (isReversed) {
            mPosition - inc
        } else {
            mPosition + inc
        }
        if (mPosition < 0) {
            mPosition = 0
        }
        if (mPosition > (mAreaWidth - mAreaHeight)) {
            mPosition = mAreaWidth - mAreaHeight
        }
    }

    /**
     * Private method that is performed when user completes the slide
     */
    private fun startAnimationComplete() {
        val animSet = AnimatorSet()

        // Animator that moves the cursor
        val finalPositionAnimator = ValueAnimator.ofInt(mPosition, mAreaWidth - mAreaHeight)
        finalPositionAnimator.addUpdateListener {
            mPosition = it.animatedValue as Int
            invalidate()
        }

        // Animator that bounce away the cursors
        val marginAnimator = ValueAnimator.ofInt(
                mActualAreaMargin,
                (mInnerRect.width() / 2).toInt() + mActualAreaMargin
        )
        marginAnimator.addUpdateListener {
            mActualAreaMargin = it.animatedValue as Int
            innerStroke?.also { stroke ->
                mInnerStrokePaint.apply {
                    val factor = (1 - it.animatedFraction)
                    alpha = checkAlphaLimit((factor * 255).toInt())
                }
            }
            invalidate()
        }
        marginAnimator.interpolator = LinearInterpolator()

        // Animator that reduces the outer area (to right)
        val areaAnimator = ValueAnimator.ofInt(0, (mAreaWidth - mAreaHeight) / 2)
        areaAnimator.addUpdateListener {
            mActualAreaWidth = it.animatedValue as Int
            if (Build.VERSION.SDK_INT >= 21) {
                invalidateOutline()
            }
            invalidate()
        }

        val tickListener = ValueAnimator.AnimatorUpdateListener {
            // We need to enable the drawing of the AnimatedVectorDrawable before starting it.
            if (!mFlagDrawTick) {
                mFlagDrawTick = true
                mTickMargin = mIconMargin
            }
        }
        val tickAnimator: ValueAnimator = createIconAnimator(this, mDrawableTick, tickListener)

        val animators = mutableListOf<Animator>()
        if (mPosition < mAreaWidth - mAreaHeight) {
            animators.add(finalPositionAnimator)
        }

        if (isAnimateCompletion) {
            animators.add(marginAnimator)
            animators.add(areaAnimator)
            animators.add(tickAnimator)
        }

        animSet.playSequentially(*animators.toTypedArray())

        animSet.duration = animDuration

        animSet.addListener(
                object : Animator.AnimatorListener {
                    override fun onAnimationStart(p0: Animator?) {
                        onSlideToActAnimationEventListener?.onSlideCompleteAnimationStarted(
                                this@SlideToActView,
                                mPositionPerc
                        )
                    }

                    override fun onAnimationCancel(p0: Animator?) {
                    }

                    override fun onAnimationEnd(p0: Animator?) {
                        mIsCompleted = true
                        onSlideToActAnimationEventListener?.onSlideCompleteAnimationEnded(
                                this@SlideToActView
                        )
                        onSlideCompleteListener?.onSlideComplete(this@SlideToActView)
                    }

                    override fun onAnimationRepeat(p0: Animator?) {
                    }
                }
        )
        animSet.start()
    }

    /**
     * Method that completes the slider
     */
    fun completeSlider() {
        if (!mIsCompleted) {
            startAnimationComplete()
        }
    }

    /**
     * Method that reset the slider
     */
    fun resetSlider() {
        if (mIsCompleted) {
            startAnimationReset()
        }
    }

    /**
     * Method that returns the 'mIsCompleted' flag
     * @return True if slider is in the Complete state
     */
    fun isCompleted(): Boolean {
        return this.mIsCompleted
    }

    /**
     * Private method that is performed when you want to reset the cursor
     */
    private fun startAnimationReset() {
        mIsCompleted = false
        val animSet = AnimatorSet()

        // Animator that reduces the tick size
        val tickAnimator = ValueAnimator.ofInt(mTickMargin, mAreaWidth / 2)
        tickAnimator.addUpdateListener {
            mTickMargin = it.animatedValue as Int
            invalidate()
        }

        // Animator that enlarges the outer area
        val areaAnimator = ValueAnimator.ofInt(mActualAreaWidth, 0)
        areaAnimator.addUpdateListener {
            // Now we can hide the tick till the next complete
            mFlagDrawTick = false
            mActualAreaWidth = it.animatedValue as Int
            if (Build.VERSION.SDK_INT >= 21) {
                invalidateOutline()
            }
            invalidate()
        }

        val positionAnimator = ValueAnimator.ofInt(mPosition, 0)
        positionAnimator.addUpdateListener {
            mPosition = it.animatedValue as Int
            invalidate()
        }

        // Animator that re-draw the cursors
        val marginAnimator = ValueAnimator.ofInt(mActualAreaMargin, mOriginAreaMargin)
        marginAnimator.addUpdateListener {
            mActualAreaMargin = it.animatedValue as Int
            innerStroke?.also { stroke ->
                mInnerStrokePaint.apply {
                    val factor = it.animatedFraction
                    alpha = checkAlphaLimit((factor * 255).toInt())
                }
            }
            invalidate()
        }
        marginAnimator.interpolator = AnticipateOvershootInterpolator(2f)

        // Animator that makes the arrow appear
        val arrowAnimator = ValueAnimator.ofInt(mArrowMargin, mIconMargin)
        arrowAnimator.addUpdateListener {
            mArrowMargin = it.animatedValue as Int
            invalidate()
        }

        marginAnimator.interpolator = OvershootInterpolator(2f)

        if (isAnimateCompletion) {
            animSet.playSequentially(
                    tickAnimator,
                    areaAnimator,
                    positionAnimator,
                    marginAnimator,
                    arrowAnimator
            )
        } else {
            animSet.playSequentially(positionAnimator)
        }

        animSet.duration = animDuration

        animSet.addListener(
                object : Animator.AnimatorListener {
                    override fun onAnimationStart(p0: Animator?) {
                        onSlideToActAnimationEventListener?.onSlideResetAnimationStarted(
                                this@SlideToActView
                        )
                    }

                    override fun onAnimationCancel(p0: Animator?) {
                    }

                    override fun onAnimationEnd(p0: Animator?) {
                        isEnabled = true
                        stopIconAnimation(mDrawableTick)
                        onSlideToActAnimationEventListener?.onSlideResetAnimationEnded(
                                this@SlideToActView
                        )
                        onSlideResetListener?.onSlideReset(this@SlideToActView)
                    }

                    override fun onAnimationRepeat(p0: Animator?) {
                    }
                }
        )
        animSet.start()
    }

    /**Check limit for alpha value**/
    private fun checkAlphaLimit(alpha: Int): Int = when {
        alpha > 255 -> 255
        alpha < 0 -> 0
        else -> alpha
    }

    /**
     * Private method to handle vibration logic, called when the cursor it moved to the end of
     * it's path.
     */
    @SuppressLint("MissingPermission")
    private fun handleVibration() {
        if (bumpVibration <= 0) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                    TAG,
                    "bumpVibration is set but permissions are unavailable." +
                            "You must have the permission android.permission.VIBRATE in " +
                            "AndroidManifest.xml to use bumpVibration"
            )
            return
        }

        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                    VibrationEffect.createOneShot(bumpVibration, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            vibrator.vibrate(bumpVibration)
        }
    }

    /**
     * Event handler for the SlideToActView animation events.
     * This event handler can be used to react to animation events from the Slide,
     * the event will be fired whenever an animation start/end.
     */
    interface OnSlideToActAnimationEventListener {

        /**
         * Called when the slide complete animation start. You can perform actions during the
         * complete animations.
         *
         * @param view The SlideToActView who created the event
         * @param threshold The mPosition (in percentage [0f,1f]) where the user has left the cursor
         */
        fun onSlideCompleteAnimationStarted(view: SlideToActView, threshold: Float)

        /**
         * Called when the slide complete animation finish. At this point the slider is stuck in the
         * center of the slider.
         *
         * @param view The SlideToActView who created the event
         */
        fun onSlideCompleteAnimationEnded(view: SlideToActView)

        /**
         * Called when the slide reset animation start. You can perform actions during the reset
         * animations.
         *
         * @param view The SlideToActView who created the event
         */
        fun onSlideResetAnimationStarted(view: SlideToActView)

        /**
         * Called when the slide reset animation finish. At this point the slider will be in the
         * ready on the left of the screen and user can interact with it.
         *
         * @param view The SlideToActView who created the event
         */
        fun onSlideResetAnimationEnded(view: SlideToActView)
    }

    /**
     * Event handler for the slide complete event.
     * Use this handler to react to slide event
     */
    interface OnSlideCompleteListener {
        /**
         * Called when user performed the slide
         * @param view The SlideToActView who created the event
         */
        fun onSlideComplete(view: SlideToActView)
    }

    /**
     * Event handler for the slide react event.
     * Use this handler to inform the user that he can slide again.
     */
    interface OnSlideResetListener {
        /**
         * Called when slides is again available
         * @param view The SlideToActView who created the event
         */
        fun onSlideReset(view: SlideToActView)
    }

    /**
     * Event handler for the user failure with the Widget.
     * You can subscribe to this event to get notified when the user is wrongly
     * interacting with the widget to eventually educate it:
     *
     * - The user clicked outside of the cursor
     * - The user slided but left when the cursor was back to zero
     *
     * You can use this listener to show a Toast or other messages.
     */
    interface OnSlideUserFailedListener {
        /**
         * Called when user failed to interact with the slider slide
         * @param view The SlideToActView who created the event
         * @param isOutside True if user pressed outside the cursor
         */
        fun onSlideFailed(view: SlideToActView, isOutside: Boolean)
    }

    /**
     * Outline provider for the SlideToActView.
     * This outline will suppress the shadow (till the moment when Android will support
     * updatable Outlines).
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private inner class SlideToActOutlineProvider : ViewOutlineProvider() {

        override fun getOutline(view: View?, outline: Outline?) {
            if (view == null || outline == null) return

            outline.setRoundRect(
                    mActualAreaWidth,
                    0,
                    mAreaWidth - mActualAreaWidth,
                    mAreaHeight,
                    mBorderRadius.toFloat()
            )
        }
    }

    data class Stroke(
            val width: Float,
            @ColorInt val color: Int
    )
}

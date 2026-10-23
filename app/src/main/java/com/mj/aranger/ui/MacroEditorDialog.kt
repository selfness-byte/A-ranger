package com.mj.aranger.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.mj.aranger.engine.VisionOcrEngine
import com.mj.aranger.model.ActionStep
import com.mj.aranger.model.ActionType
import com.mj.aranger.model.BranchBehavior
import com.mj.aranger.model.ConstraintType
import com.mj.aranger.model.GlobalActionOption
import com.mj.aranger.model.MacroRule
import com.mj.aranger.model.TriggerType
import com.mj.aranger.store.MacroRepository
import java.util.UUID

object MacroEditorDialog {

    fun show(
        context: Context,
        initialRule: MacroRule? = null,
        onSaved: (MacroRule) -> Unit
    ) {
        val isEdit = initialRule != null
        val workingActions = (initialRule?.actions ?: emptyList()).toMutableList()

        val scrollView = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
        }
        scrollView.addView(container)

        // 1. 매크로 이름
        container.addView(sectionLabel(context, "📌 매크로 이름"))
        val nameInput = styledEditText(context, "예: 광고 건너뛰기 / 이미지 서치 터치").apply {
            setText(initialRule?.name ?: "")
        }
        container.addView(nameInput)

        // 2. 트리거(Trigger) 설정
        container.addView(sectionLabel(context, "⚡ 트리거 (언제 실행할까요?)"))
        val triggerTypes = TriggerType.entries
        val triggerSpinner = styledSpinner(context, triggerTypes.map { it.title })
        val initTriggerIdx = initialRule?.let { triggerTypes.indexOf(it.triggerType) }?.coerceAtLeast(0) ?: 0
        triggerSpinner.setSelection(initTriggerIdx)
        container.addView(triggerSpinner)

        val triggerParamInput = styledEditText(context, triggerTypes[initTriggerIdx].hint).apply {
            setText(initialRule?.triggerParam ?: "")
        }
        container.addView(triggerParamInput)

        val loadPixelTriggerBtn = TextView(context).apply {
            val pt = MacroRepository.getPickedPoint(context)
            val hex = MacroRepository.getPickedColorHex(context)
            text = "🎨 스포이드 추출값 (${pt.first},${pt.second},$hex) 자동 입력"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 8).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(context, 1), Color.parseColor("#38BDF8"))
            }
            setOnClickListener {
                val p = MacroRepository.getPickedPoint(context)
                val c = MacroRepository.getPickedColorHex(context)
                triggerParamInput.setText("${p.first},${p.second},$c")
            }
        }
        container.addView(loadPixelTriggerBtn)

        triggerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = triggerTypes[position]
                triggerParamInput.hint = selected.hint
                triggerParamInput.visibility = when (selected) {
                    TriggerType.MANUAL_FLOATING,
                    TriggerType.CHARGING_CONNECTED,
                    TriggerType.SHAKE_DEVICE -> View.GONE
                    else -> View.VISIBLE
                }
                loadPixelTriggerBtn.visibility =
                    if (selected == TriggerType.PIXEL_COLOR_MATCH) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 3. 제약 조건(Constraint) 설정
        container.addView(sectionLabel(context, "🔍 제약 조건 (어떤 상태에서만 실행할까요?)"))
        val constraintTypes = ConstraintType.entries
        val constraintSpinner = styledSpinner(context, constraintTypes.map { it.title })
        val initConstraintIdx = initialRule?.let { constraintTypes.indexOf(it.constraintType) }?.coerceAtLeast(0) ?: 0
        constraintSpinner.setSelection(initConstraintIdx)
        container.addView(constraintSpinner)

        val constraintParamInput = styledEditText(context, constraintTypes[initConstraintIdx].hint).apply {
            setText(initialRule?.constraintParam ?: "")
        }
        container.addView(constraintParamInput)

        constraintSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = constraintTypes[position]
                constraintParamInput.hint = selected.hint
                constraintParamInput.visibility = when (selected) {
                    ConstraintType.NONE, ConstraintType.CHARGING_ONLY -> View.GONE
                    else -> View.VISIBLE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 4. 재발동 대기시간(쿨다운) 및 매크로 전체 반복(Loop) 설정
        container.addView(sectionLabel(context, "⏱️ 재발동 쿨다운 (밀리초, 연속 중복 실행 방지)"))
        val cooldownInput = styledEditText(
            context,
            "기본 1500 (1.5초)",
            InputType.TYPE_CLASS_NUMBER
        ).apply {
            setText((initialRule?.cooldownMs ?: 1500L).toString())
        }
        container.addView(cooldownInput)

        container.addView(sectionLabel(context, "🔁 매크로 전체 반복 횟수 (0 입력 시 무한 반복, 기본 1회)"))
        val macroRepeatInput = styledEditText(
            context,
            "0 = 무한 반복 / 1 = 1회 실행 / N = N회 반복",
            InputType.TYPE_CLASS_NUMBER
        ).apply {
            setText((initialRule?.repeatCount ?: 1).toString())
        }
        val macroRepeatDelayInput = styledEditText(
            context,
            "전체 사이클 간 대기 시간 ms (기본 500)",
            InputType.TYPE_CLASS_NUMBER
        ).apply {
            setText((initialRule?.repeatDelayMs ?: 500L).toString())
        }

        val loopPresetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 6)
            }
        }
        loopPresetRow.addView(smallActionButton(context, "1회 실행", "#334155") {
            macroRepeatInput.setText("1")
        })
        loopPresetRow.addView(smallActionButton(context, "10회 반복", "#0284C7") {
            macroRepeatInput.setText("10")
        })
        loopPresetRow.addView(smallActionButton(context, "100회 반복", "#7C3AED") {
            macroRepeatInput.setText("100")
        })
        loopPresetRow.addView(smallActionButton(context, "♾️ 무한 반복(0)", "#E11D48") {
            macroRepeatInput.setText("0")
        })
        container.addView(loopPresetRow)
        container.addView(macroRepeatInput)
        container.addView(macroRepeatDelayInput)

        // 5. 액션(Actions) 단계 목록 (클릭 시 수정 / 복제 / 순서 이동 지원)
        container.addView(sectionLabel(context, "🚀 실행할 액션 목록 (터치하여 수정 · 복제 · 순서 변경)"))
        val actionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(actionsContainer)

        fun renderActions() {
            actionsContainer.removeAllViews()
            if (workingActions.isEmpty()) {
                val emptyTv = TextView(context).apply {
                    text = "아직 등록된 액션이 없습니다. 아래 [+ 액션 추가] 버튼을 눌러 화면 터치, 이미지 서치, 비전 OCR, 조건 분기 등을 추가하세요."
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10))
                }
                actionsContainer.addView(emptyTv)
                return
            }

            workingActions.forEachIndexed { idx, step ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 12), dp(context, 10), dp(context, 10), dp(context, 10))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 10).toFloat()
                        setColor(Color.parseColor("#1E293B"))
                        setStroke(dp(context, 1), Color.parseColor("#334155"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(context, 8)
                    }
                }

                val summaryTv = TextView(context).apply {
                    text = "${idx + 1}. ${step.toSummary()}"
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, 0, dp(context, 6))
                    setOnClickListener {
                        showActionStepBuilder(context, initialStep = step) { updated ->
                            workingActions[idx] = updated
                            renderActions()
                        }
                    }
                }
                card.addView(summaryTv)

                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }

                btnRow.addView(smallActionButton(context, "✏️ 수정", "#0284C7") {
                    showActionStepBuilder(context, initialStep = step) { updated ->
                        workingActions[idx] = updated
                        renderActions()
                    }
                })

                btnRow.addView(smallActionButton(context, "📋 복제", "#0D9488") {
                    workingActions.add(idx + 1, step.copy(id = UUID.randomUUID().toString()))
                    renderActions()
                })

                if (idx > 0) {
                    btnRow.addView(smallActionButton(context, "▲", "#334155") {
                        val tmp = workingActions[idx - 1]
                        workingActions[idx - 1] = workingActions[idx]
                        workingActions[idx] = tmp
                        renderActions()
                    })
                }

                if (idx < workingActions.size - 1) {
                    btnRow.addView(smallActionButton(context, "▼", "#334155") {
                        val tmp = workingActions[idx + 1]
                        workingActions[idx + 1] = workingActions[idx]
                        workingActions[idx] = tmp
                        renderActions()
                    })
                }

                btnRow.addView(smallActionButton(context, "삭제", "#B91C1C") {
                    workingActions.removeAt(idx)
                    renderActions()
                })

                card.addView(btnRow)
                actionsContainer.addView(card)
            }
        }

        renderActions()

        val addActionBtn = TextView(context).apply {
            text = "+ 화면 제어 / 비전 / 조건분기 액션 추가"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0F172A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 10).toFloat()
                setColor(Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 6)
            }
            setOnClickListener {
                showActionStepBuilder(context, initialStep = null) { newStep ->
                    workingActions.add(newStep)
                    renderActions()
                }
            }
        }
        container.addView(addActionBtn)

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (isEdit) "매크로 편집" else "새 매크로 만들기")
            .setView(scrollView)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim().ifEmpty { "내 자동화 매크로" }
                if (workingActions.isEmpty()) {
                    Toast.makeText(context, "최소 1개 이상의 액션을 추가해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedTrigger = triggerTypes[triggerSpinner.selectedItemPosition]
                val selectedConstraint = constraintTypes[constraintSpinner.selectedItemPosition]
                val cooldown = cooldownInput.text.toString().trim().toLongOrNull()?.coerceAtLeast(200L) ?: 1500L
                val macroRepeat = (macroRepeatInput.text.toString().trim().toIntOrNull() ?: 1).coerceIn(0, 9999)
                val macroRepeatDelay = (macroRepeatDelayInput.text.toString().trim().toLongOrNull() ?: 500L).coerceAtLeast(100L)

                val savedRule = (initialRule ?: MacroRule(name = name)).copy(
                    name = name,
                    triggerType = selectedTrigger,
                    triggerParam = triggerParamInput.text.toString().trim(),
                    constraintType = selectedConstraint,
                    constraintParam = constraintParamInput.text.toString().trim(),
                    cooldownMs = cooldown,
                    repeatCount = macroRepeat,
                    repeatDelayMs = macroRepeatDelay,
                    actions = workingActions.toList()
                )
                onSaved(savedRule)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showActionStepBuilder(
        context: Context,
        initialStep: ActionStep? = null,
        onSaved: (ActionStep) -> Unit
    ) {
        val isEditingStep = initialStep != null
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
        }
        scroll.addView(box)

        box.addView(sectionLabel(context, "액션 종류 선택"))
        val actionTypes = ActionType.entries
        val typeSpinner = styledSpinner(context, actionTypes.map { it.title })
        val initialTypeIdx = initialStep?.let { actionTypes.indexOf(it.type) }?.coerceAtLeast(0) ?: 0
        typeSpinner.setSelection(initialTypeIdx)
        box.addView(typeSpinner)

        val dynamicFields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        box.addView(dynamicFields)

        val picked = MacroRepository.getPickedPoint(context)
        val pickedNode = MacroRepository.getPickedNodeInfo(context)
        val pickedColor = MacroRepository.getPickedColorHex(context)

        var activeTemplateJson = initialStep?.templateJson?.takeIf { it.isNotBlank() }
            ?: MacroRepository.getLastCapturedTemplateJson(context)

        val xInput = styledEditText(context, "X 좌표 (예: 540)", InputType.TYPE_CLASS_NUMBER).apply {
            setText((initialStep?.x ?: picked.first).toString())
        }
        val yInput = styledEditText(context, "Y 좌표 (예: 1200)", InputType.TYPE_CLASS_NUMBER).apply {
            setText((initialStep?.y ?: picked.second).toString())
        }
        val x2Input = styledEditText(context, "끝 X2 좌표 / 허용 오차 (예: 540 또는 34)", InputType.TYPE_CLASS_NUMBER).apply {
            setText((initialStep?.x2 ?: 540).toString())
        }
        val y2Input = styledEditText(context, "끝 Y2 좌표 (예: 450)", InputType.TYPE_CLASS_NUMBER).apply {
            setText((initialStep?.y2 ?: 450).toString())
        }
        val colorHexInput = styledEditText(context, "비교할 색상 #RRGGBB (예: #38BDF8)").apply {
            setText(initialStep?.colorHex ?: pickedColor)
        }
        val durationInput = styledEditText(context, "지속/대기 시간 ms (예: 100)", InputType.TYPE_CLASS_NUMBER).apply {
            setText((initialStep?.durationMs ?: 100L).toString())
        }
        val repeatCountInput = styledEditText(context, "반복 횟수 (기본 1)", InputType.TYPE_CLASS_NUMBER).apply {
            setText((initialStep?.repeatCount ?: 1).toString())
        }
        val repeatIntervalInput = styledEditText(context, "반복 간격 ms (기본 250)", InputType.TYPE_CLASS_NUMBER).apply {
            setText((initialStep?.repeatIntervalMs ?: 250L).toString())
        }
        val textParamInput = styledEditText(context, "찾을 글자 / 뷰 ID / 텍스트 / URL 입력").apply {
            setText(initialStep?.textParam ?: "")
        }

        val globalOptions = GlobalActionOption.entries
        val globalSpinner = styledSpinner(context, globalOptions.map { it.label })
        if (initialStep != null) {
            val gIdx = globalOptions.indexOfFirst { it.name == initialStep.globalActionName }.coerceAtLeast(0)
            globalSpinner.setSelection(gIdx)
        }

        val branchOptions = BranchBehavior.entries
        val branchSpinner = styledSpinner(context, branchOptions.map { it.label })
        if (initialStep != null) {
            val code = if (initialStep.type == ActionType.BRANCH_IF_COLOR) initialStep.x2 else initialStep.x
            val bIdx = branchOptions.indexOfFirst { it.code == code }.coerceAtLeast(0)
            branchSpinner.setSelection(bIdx)
        }

        fun buildLoadCoordButton(): View = TextView(context).apply {
            val pt = MacroRepository.getPickedPoint(context)
            val hex = MacroRepository.getPickedColorHex(context)
            text = "📌 조준기/스포이드 저장값 (${pt.first}, ${pt.second} · $hex) 불러오기"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 8).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(context, 1), Color.parseColor("#38BDF8"))
            }
            setOnClickListener {
                val latest = MacroRepository.getPickedPoint(context)
                val latestHex = MacroRepository.getPickedColorHex(context)
                xInput.setText(latest.first.toString())
                yInput.setText(latest.second.toString())
                colorHexInput.setText(latestHex)
                Toast.makeText(
                    context,
                    "좌표 (${latest.first}, ${latest.second}) 및 색상 $latestHex 적용됨",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        fun buildImageTemplatePreviewBox(): View {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 10).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(context, 1), Color.parseColor("#38BDF8"))
                }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val previewImg = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 52), dp(context, 52)).apply {
                    marginEnd = dp(context, 12)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 6).toFloat()
                    setColor(Color.parseColor("#0F172A"))
                    setStroke(dp(context, 1), Color.parseColor("#475569"))
                }
            }
            val descTv = TextView(context).apply {
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            fun refreshPreview() {
                val bmp = VisionOcrEngine.decodeThumbnailBitmap(activeTemplateJson)
                if (bmp != null) {
                    previewImg.setImageBitmap(bmp)
                    descTv.text = "✅ 캡처된 아이콘 템플릿:\n${VisionOcrEngine.getTemplateSummary(activeTemplateJson)}"
                } else {
                    previewImg.setImageDrawable(null)
                    descTv.text = "⚠️ 아직 캡처된 아이콘 조각이 없습니다.\n플로팅 리모컨 [🎯 조준] → [🖼️ 조각캡처]를 눌러 원하는 아이콘을 캡처하세요."
                }
            }
            refreshPreview()

            row.addView(previewImg)
            row.addView(descTv)
            wrap.addView(row)

            val reloadBtn = TextView(context).apply {
                text = "🔄 최근 리모컨으로 캡처한 아이콘 조각 다시 불러오기"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(context, 8), 0, 0)
                setOnClickListener {
                    activeTemplateJson = MacroRepository.getLastCapturedTemplateJson(context)
                    refreshPreview()
                }
            }
            wrap.addView(reloadBtn)
            return wrap
        }

        var firstRender = true
        fun rebuildFields(selected: ActionType) {
            dynamicFields.removeAllViews()
            val preserveValues = isEditingStep && firstRender
            firstRender = false

            when (selected) {
                ActionType.TAP_XY -> {
                    dynamicFields.addView(buildLoadCoordButton())
                    dynamicFields.addView(sectionLabel(context, "터치할 X / Y 좌표"))
                    dynamicFields.addView(xInput)
                    dynamicFields.addView(yInput)
                    dynamicFields.addView(sectionLabel(context, "반복 횟수 및 반복 간격(ms)"))
                    dynamicFields.addView(repeatCountInput)
                    dynamicFields.addView(repeatIntervalInput)
                    dynamicFields.addView(sectionLabel(context, "터치 유지 시간(ms)"))
                    if (!preserveValues) durationInput.setText("90")
                    dynamicFields.addView(durationInput)
                }
                ActionType.LONG_PRESS_XY -> {
                    dynamicFields.addView(buildLoadCoordButton())
                    dynamicFields.addView(sectionLabel(context, "길게 누를 X / Y 좌표"))
                    dynamicFields.addView(xInput)
                    dynamicFields.addView(yInput)
                    dynamicFields.addView(sectionLabel(context, "길게 누를 시간(ms, 기본 800ms)"))
                    if (!preserveValues) durationInput.setText("800")
                    dynamicFields.addView(durationInput)
                }
                ActionType.TAP_IF_COLOR -> {
                    dynamicFields.addView(buildLoadCoordButton())
                    dynamicFields.addView(sectionLabel(context, "검사 및 터치할 X / Y 좌표"))
                    dynamicFields.addView(xInput)
                    dynamicFields.addView(yInput)
                    dynamicFields.addView(sectionLabel(context, "일치해야 하는 픽셀 색상 (#RRGGBB)"))
                    dynamicFields.addView(colorHexInput)
                    dynamicFields.addView(sectionLabel(context, "터치 유지 시간(ms)"))
                    if (!preserveValues) durationInput.setText("95")
                    dynamicFields.addView(durationInput)
                }
                ActionType.LOOP_UNTIL_COLOR -> {
                    dynamicFields.addView(buildLoadCoordButton())
                    dynamicFields.addView(sectionLabel(context, "검사 및 터치할 X / Y 좌표"))
                    dynamicFields.addView(xInput)
                    dynamicFields.addView(yInput)
                    dynamicFields.addView(sectionLabel(context, "기다릴 목표 픽셀 색상 (#RRGGBB)"))
                    dynamicFields.addView(colorHexInput)
                    dynamicFields.addView(sectionLabel(context, "최대 검사 횟수 및 검사 간격(ms)"))
                    if (!preserveValues) {
                        repeatCountInput.setText("25")
                        repeatIntervalInput.setText("400")
                    }
                    dynamicFields.addView(repeatCountInput)
                    dynamicFields.addView(repeatIntervalInput)
                }
                ActionType.CLICK_IMAGE_TEMPLATE -> {
                    dynamicFields.addView(buildImageTemplatePreviewBox())
                    dynamicFields.addView(sectionLabel(context, "색상 허용 오차 (15~60, 기본 34) 및 터치 유지 시간(ms)"))
                    if (!preserveValues) {
                        x2Input.setText("34")
                        durationInput.setText("95")
                    }
                    dynamicFields.addView(x2Input)
                    dynamicFields.addView(durationInput)
                }
                ActionType.LOOP_UNTIL_IMAGE -> {
                    dynamicFields.addView(buildImageTemplatePreviewBox())
                    dynamicFields.addView(sectionLabel(context, "최대 탐색 횟수 및 탐색 간격(ms)"))
                    if (!preserveValues) {
                        repeatCountInput.setText("25")
                        repeatIntervalInput.setText("450")
                        x2Input.setText("34")
                    }
                    dynamicFields.addView(repeatCountInput)
                    dynamicFields.addView(repeatIntervalInput)
                    dynamicFields.addView(sectionLabel(context, "색상 허용 오차 (15~60, 기본 34)"))
                    dynamicFields.addView(x2Input)
                }
                ActionType.OCR_CLICK_TEXT -> {
                    dynamicFields.addView(sectionLabel(context, "👁️ 화면에서 비전 OCR로 찾아 터치할 글자 (캔버스/게임/일반 화면 모두 지원)"))
                    textParamInput.hint = "예: 건너뛰기, 확인, START, 보상, 닫기"
                    dynamicFields.addView(textParamInput)
                    dynamicFields.addView(sectionLabel(context, "터치 유지 시간(ms)"))
                    if (!preserveValues) durationInput.setText("95")
                    dynamicFields.addView(durationInput)
                }
                ActionType.OCR_LOOP_UNTIL_TEXT -> {
                    dynamicFields.addView(buildLoadCoordButton())
                    dynamicFields.addView(sectionLabel(context, "👁️ 멈출 조건: 어떤 글자가 비전 OCR로 인식될 때까지 반복 터치할까요?"))
                    textParamInput.hint = "예: 성공, 완료, VICTORY, 결과"
                    dynamicFields.addView(textParamInput)
                    dynamicFields.addView(sectionLabel(context, "대기 중 반복 터치할 X / Y 좌표"))
                    dynamicFields.addView(xInput)
                    dynamicFields.addView(yInput)
                    dynamicFields.addView(sectionLabel(context, "최대 반복 횟수 및 간격(ms)"))
                    if (!preserveValues) {
                        repeatCountInput.setText("25")
                        repeatIntervalInput.setText("500")
                    }
                    dynamicFields.addView(repeatCountInput)
                    dynamicFields.addView(repeatIntervalInput)
                }
                ActionType.BRANCH_IF_TEXT -> {
                    dynamicFields.addView(sectionLabel(context, "🔀 검사할 화면 텍스트(글자)"))
                    textParamInput.hint = "예: 강화 성공, 매진, 오류, 완료"
                    dynamicFields.addView(textParamInput)
                    dynamicFields.addView(sectionLabel(context, "분기 동작 선택 (진행 / 루프 탈출 / 다음 단계 건너뛰기)"))
                    dynamicFields.addView(branchSpinner)
                }
                ActionType.BRANCH_IF_COLOR -> {
                    dynamicFields.addView(buildLoadCoordButton())
                    dynamicFields.addView(sectionLabel(context, "🔀 검사할 X / Y 좌표 및 목표 색상 (#RRGGBB)"))
                    dynamicFields.addView(xInput)
                    dynamicFields.addView(yInput)
                    dynamicFields.addView(colorHexInput)
                    dynamicFields.addView(sectionLabel(context, "분기 동작 선택 (진행 / 루프 탈출 / 다음 단계 건너뛰기)"))
                    dynamicFields.addView(branchSpinner)
                }
                ActionType.CUSTOM_GESTURE_PATH -> {
                    val savedPath = MacroRepository.getLastRecordedPathJson(context)
                    val infoTv = TextView(context).apply {
                        text = if (savedPath.isNotBlank()) {
                            "✅ 최근 [📹 궤적] 버튼으로 녹화한 자유 곡선·패턴이 자동으로 연결됩니다."
                        } else {
                            "ℹ️ 플로팅 리모컨의 [📹 궤적] 버튼을 눌러 화면에 곡선/패턴을 그리면 해당 궤적이 재생됩니다."
                        }
                        setTextColor(Color.parseColor("#38BDF8"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
                    }
                    dynamicFields.addView(infoTv)
                    dynamicFields.addView(sectionLabel(context, "재생 시간(ms) 및 반복 횟수"))
                    if (!preserveValues) {
                        durationInput.setText("650")
                        repeatCountInput.setText("1")
                    }
                    dynamicFields.addView(durationInput)
                    dynamicFields.addView(repeatCountInput)
                }
                ActionType.CLICK_TEXT -> {
                    dynamicFields.addView(sectionLabel(context, "화면에서 찾아 클릭할 글자(버튼 텍스트)"))
                    textParamInput.hint = "예: 건너뛰기, 확인, 허용, 닫기"
                    if (pickedNode.first.isNotBlank() && textParamInput.text.isEmpty()) {
                        textParamInput.setText(pickedNode.first)
                    }
                    dynamicFields.addView(textParamInput)
                }
                ActionType.CLICK_VIEW_ID -> {
                    dynamicFields.addView(sectionLabel(context, "화면에서 찾아 클릭할 뷰 ID (Resource ID)"))
                    textParamInput.hint = "예: btn_close, skip_ad_button ([🔍 분석]에서 확인 가능)"
                    if (pickedNode.second.isNotBlank() && textParamInput.text.isEmpty()) {
                        textParamInput.setText(pickedNode.second.substringAfterLast('/'))
                    }
                    dynamicFields.addView(textParamInput)
                }
                ActionType.LOOP_UNTIL_TEXT -> {
                    dynamicFields.addView(buildLoadCoordButton())
                    dynamicFields.addView(sectionLabel(context, "멈출 조건: 화면에 어떤 글자가 나타날 때까지 터치할까요?"))
                    textParamInput.hint = "예: 완료, 성공, 수령 (이 글자가 뜨면 루프 종료)"
                    dynamicFields.addView(textParamInput)
                    dynamicFields.addView(sectionLabel(context, "반복 터치할 X / Y 좌표"))
                    dynamicFields.addView(xInput)
                    dynamicFields.addView(yInput)
                    dynamicFields.addView(sectionLabel(context, "최대 반복 횟수 및 터치 간격(ms)"))
                    if (!preserveValues) {
                        repeatCountInput.setText("30")
                        repeatIntervalInput.setText("500")
                    }
                    dynamicFields.addView(repeatCountInput)
                    dynamicFields.addView(repeatIntervalInput)
                }
                ActionType.SWIPE -> {
                    dynamicFields.addView(sectionLabel(context, "스와이프 방향 빠른 프리셋"))
                    val presetRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    presetRow.addView(smallActionButton(context, "⬆️ 위로 스크롤", "#334155") {
                        xInput.setText("540"); yInput.setText("1750")
                        x2Input.setText("540"); y2Input.setText("450")
                        durationInput.setText("320")
                    })
                    presetRow.addView(smallActionButton(context, "⬇️ 아래로", "#334155") {
                        xInput.setText("540"); yInput.setText("500")
                        x2Input.setText("540"); y2Input.setText("1750")
                        durationInput.setText("320")
                    })
                    presetRow.addView(smallActionButton(context, "⬅️ 좌로", "#334155") {
                        xInput.setText("920"); yInput.setText("1100")
                        x2Input.setText("160"); y2Input.setText("1100")
                        durationInput.setText("280")
                    })
                    presetRow.addView(smallActionButton(context, "➡️ 우로", "#334155") {
                        xInput.setText("160"); yInput.setText("1100")
                        x2Input.setText("920"); y2Input.setText("1100")
                        durationInput.setText("280")
                    })
                    dynamicFields.addView(presetRow)

                    dynamicFields.addView(sectionLabel(context, "시작 좌표 (X1, Y1)"))
                    dynamicFields.addView(xInput)
                    dynamicFields.addView(yInput)
                    dynamicFields.addView(sectionLabel(context, "끝 좌표 (X2, Y2)"))
                    dynamicFields.addView(x2Input)
                    dynamicFields.addView(y2Input)
                    dynamicFields.addView(sectionLabel(context, "스와이프 시간(ms) 및 반복 횟수"))
                    if (!preserveValues) durationInput.setText("320")
                    dynamicFields.addView(durationInput)
                    dynamicFields.addView(repeatCountInput)
                }
                ActionType.INPUT_TEXT -> {
                    dynamicFields.addView(sectionLabel(context, "입력창에 자동으로 타이핑할 내용 ({noti}, {time} 변수 지원)"))
                    textParamInput.hint = "예: 안녕하세요 / {noti}"
                    dynamicFields.addView(textParamInput)
                }
                ActionType.CLIPBOARD_PASTE -> {
                    dynamicFields.addView(sectionLabel(context, "클립보드에 복사 후 즉시 붙여넣을 텍스트 ({noti}, {time} 지원)"))
                    textParamInput.hint = "일반 타이핑이 막힌 입력창에도 빠르게 붙여넣습니다"
                    dynamicFields.addView(textParamInput)
                }
                ActionType.WEBHOOK_HTTP -> {
                    dynamicFields.addView(sectionLabel(context, "호출할 웹훅 URL ({noti}=최근알림, {pkg}=현재앱, {time}=시각)"))
                    textParamInput.hint = "예: https://example.com/hook?msg={noti}&app={pkg}"
                    dynamicFields.addView(textParamInput)
                }
                ActionType.WAIT -> {
                    dynamicFields.addView(sectionLabel(context, "대기할 시간 (밀리초, 1000 = 1초)"))
                    if (!preserveValues) durationInput.setText("1000")
                    dynamicFields.addView(durationInput)
                }
                ActionType.GLOBAL_ACTION -> {
                    dynamicFields.addView(sectionLabel(context, "실행할 시스템 동작 선택"))
                    dynamicFields.addView(globalSpinner)
                }
                ActionType.MEDIA_VOLUME -> {
                    dynamicFields.addView(sectionLabel(context, "설정할 미디어 볼륨 (0 ~ 100%)"))
                    if (!preserveValues) xInput.setText("30")
                    dynamicFields.addView(xInput)
                }
                ActionType.LAUNCH_APP -> {
                    dynamicFields.addView(sectionLabel(context, "실행할 앱 이름 또는 패키지명"))
                    textParamInput.hint = "예: 유튜브, 카카오톡, com.android.chrome"
                    dynamicFields.addView(textParamInput)
                }
                ActionType.TOAST_VIBRATE -> {
                    dynamicFields.addView(sectionLabel(context, "화면에 띄울 토스트 알림 문구"))
                    textParamInput.hint = "예: 자동 클릭 완료!"
                    dynamicFields.addView(textParamInput)
                }
            }
        }

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                rebuildFields(actionTypes[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rebuildFields(actionTypes[initialTypeIdx])

        AlertDialog.Builder(context)
            .setTitle(if (isEditingStep) "액션 단계 수정" else "액션 단계 추가")
            .setView(scroll)
            .setPositiveButton(if (isEditingStep) "수정 저장" else "추가") { _, _ ->
                val type = actionTypes[typeSpinner.selectedItemPosition]
                val selectedBranchCode = branchOptions[branchSpinner.selectedItemPosition].code
                val parsedX = when (type) {
                    ActionType.BRANCH_IF_TEXT -> selectedBranchCode
                    else -> xInput.text.toString().toIntOrNull() ?: 540
                }
                val parsedX2 = when (type) {
                    ActionType.BRANCH_IF_COLOR -> selectedBranchCode
                    else -> x2Input.text.toString().toIntOrNull() ?: 540
                }

                val step = ActionStep(
                    id = initialStep?.id ?: UUID.randomUUID().toString(),
                    type = type,
                    x = parsedX,
                    y = yInput.text.toString().toIntOrNull() ?: 1200,
                    x2 = parsedX2,
                    y2 = y2Input.text.toString().toIntOrNull() ?: 450,
                    durationMs = durationInput.text.toString().toLongOrNull() ?: 120L,
                    repeatCount = (repeatCountInput.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1),
                    repeatIntervalMs = (repeatIntervalInput.text.toString().toLongOrNull() ?: 250L).coerceAtLeast(40L),
                    textParam = textParamInput.text.toString().trim(),
                    globalActionName = globalOptions[globalSpinner.selectedItemPosition].name,
                    colorHex = colorHexInput.text.toString().trim().ifBlank { "#38BDF8" },
                    gesturePathJson = if (type == ActionType.CUSTOM_GESTURE_PATH) {
                        initialStep?.gesturePathJson?.takeIf { it.isNotBlank() }
                            ?: MacroRepository.getLastRecordedPathJson(context)
                    } else {
                        ""
                    },
                    templateJson = if (type == ActionType.CLICK_IMAGE_TEMPLATE || type == ActionType.LOOP_UNTIL_IMAGE) {
                        activeTemplateJson
                    } else {
                        ""
                    }
                )
                onSaved(step)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#38BDF8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(context, 2), dp(context, 12), dp(context, 2), dp(context, 6))
    }

    private fun styledEditText(
        context: Context,
        hintText: String,
        inputTypeFlags: Int = InputType.TYPE_CLASS_TEXT
    ): EditText = EditText(context).apply {
        hint = hintText
        inputType = inputTypeFlags
        setTextColor(Color.parseColor("#F8FAFC"))
        setHintTextColor(Color.parseColor("#64748B"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 8).toFloat()
            setColor(Color.parseColor("#1E293B"))
            setStroke(dp(context, 1), Color.parseColor("#334155"))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, 6)
        }
    }

    private fun styledSpinner(context: Context, items: List<String>): Spinner = Spinner(context).apply {
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.parseColor("#F8FAFC"))
                v.textSize = 14f
                v.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setBackgroundColor(Color.parseColor("#1E293B"))
                v.setTextColor(Color.parseColor("#F8FAFC"))
                v.textSize = 14f
                v.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
                return v
            }
        }
        this.adapter = adapter
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 8).toFloat()
            setColor(Color.parseColor("#1E293B"))
            setStroke(dp(context, 1), Color.parseColor("#475569"))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, 6)
        }
    }

    private fun smallActionButton(
        context: Context,
        label: String,
        bgHex: String,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 6).toFloat()
            setColor(Color.parseColor(bgHex))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(context, 4)
            marginEnd = dp(context, 4)
        }
        setOnClickListener { onClick() }
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}

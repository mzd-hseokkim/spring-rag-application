INSERT INTO document_template (name, description, output_format, section_schema, system_prompt, template_path, is_public)
VALUES
(
    '기술 제안서',
    'RFP 기반 기술 제안서를 생성합니다. 문제 분석, 솔루션 제안, 추진 일정 등을 포함합니다.',
    'HTML',
    '{
        "sections": [
            {"key": "executive_summary", "title": "요약", "required": true, "maxLength": 500, "prompt": "제안의 핵심 내용을 3-5문장으로 요약하세요."},
            {"key": "problem_analysis", "title": "문제 분석", "required": true, "maxLength": 2000, "prompt": "제안요청서에 명시된 문제를 분석하고 핵심 이슈를 도출하세요."},
            {"key": "proposed_solution", "title": "제안 솔루션", "required": true, "maxLength": 3000, "prompt": "문제를 해결할 구체적인 방안을 제시하세요."},
            {"key": "timeline", "title": "추진 일정", "required": false, "type": "table", "prompt": "단계별 추진 일정을 표로 작성하세요."},
            {"key": "expected_effects", "title": "기대 효과", "required": true, "maxLength": 1500, "prompt": "제안 솔루션 도입 시 기대되는 효과를 정리하세요."}
        ]
    }',
    '당신은 전문 기술 제안서 작성자입니다. 명확하고 설득력 있는 문장을 사용하세요. 기술 용어는 정확하게, 비즈니스 가치는 구체적인 수치로 표현하세요.',
    'generation/proposal',
    true
),
(
    '사업 보고서',
    '사업 현황 보고서를 생성합니다. 실적, 분석, 향후 계획 등을 포함합니다.',
    'HTML',
    '{
        "sections": [
            {"key": "overview", "title": "사업 개요", "required": true, "maxLength": 800, "prompt": "사업의 배경과 목적을 간결하게 서술하세요."},
            {"key": "performance", "title": "실적 분석", "required": true, "maxLength": 2500, "prompt": "주요 성과와 실적을 데이터와 함께 분석하세요."},
            {"key": "issues", "title": "이슈 및 개선점", "required": true, "maxLength": 2000, "prompt": "발견된 문제점과 개선 방향을 제시하세요."},
            {"key": "future_plan", "title": "향후 계획", "required": true, "maxLength": 1500, "prompt": "향후 추진 계획과 목표를 구체적으로 서술하세요."}
        ]
    }',
    '당신은 비즈니스 보고서 작성 전문가입니다. 객관적이고 분석적인 톤을 유지하며, 데이터 기반의 인사이트를 제공하세요.',
    'generation/proposal',
    true
);

"""Content of the sample 'Northwind Employee Handbook' and its evaluation questions.

Every question has exactly one page that answers it, so we can check citations (NFR-21).
"""

PAGES = [
    (
        "Welcome to Northwind",
        "Northwind Technologies was founded in 2009 in Pune, India. Today the company employs about 1,200 people "
        "across offices in Pune, Bengaluru and Hyderabad. Our chief executive officer is Meera Iyer. This handbook "
        "explains the policies that apply to every full-time employee.",
    ),
    (
        "Working Hours",
        "The standard working week is 40 hours, Monday to Friday. Core hours, when everyone must be available for "
        "meetings, are 10:00 to 16:00. Employees may start their working day at any time between 8:00 and 11:00. "
        "Overtime must be approved in advance by your manager.",
    ),
    (
        "Annual Leave and Sick Leave",
        "Each full-time employee is entitled to 24 days of paid annual leave per calendar year. Up to 10 unused "
        "annual leave days can be carried over to the next year. Employees also receive 12 days of paid sick leave "
        "per year; a medical certificate is required for sick leave longer than 3 consecutive days.",
    ),
    (
        "Parental Leave",
        "Birth mothers receive 26 weeks of paid maternity leave. Fathers and partners receive 4 weeks of paid "
        "paternity leave, to be taken within six months of the birth. Employees who adopt a child receive 12 weeks "
        "of paid adoption leave.",
    ),
    (
        "Remote Work",
        "Employees may work from home up to 3 days per week with their manager's agreement. Northwind pays a home "
        "internet allowance of 1,500 rupees per month. While working remotely you must be reachable on Slack during "
        "core hours.",
    ),
    (
        "Expenses and Travel",
        "Expense claims must be submitted within 30 days of the expense, with receipts. The daily meal allowance on "
        "business trips is 800 rupees. Flights shorter than 6 hours are booked in economy class; longer flights may "
        "be booked in premium economy.",
    ),
    (
        "Equipment and IT Security",
        "Every employee receives a company laptop, which is replaced every 3 years. Passwords must be at least 14 "
        "characters long and multi-factor authentication is mandatory. A lost or stolen device must be reported to "
        "it-security@northwind.example within 24 hours.",
    ),
    (
        "Learning and Development",
        "Each employee has a learning budget of 40,000 rupees per year for courses, books and conferences. Employees "
        "may also take 5 paid learning days per year. Fees for approved professional certifications are reimbursed "
        "after the exam is passed.",
    ),
    (
        "Performance Reviews",
        "Performance reviews take place twice a year, in April and October. Managers rate performance on a scale "
        "from 1 to 5. Promotions and salary increases are announced once a year, after the April review cycle.",
    ),
    (
        "Health Benefits",
        "Health insurance covers the employee, their spouse and up to two children, with cover of up to 5 lakh "
        "rupees per year. Employees are offered a free annual health check-up. Northwind pays 50 percent of a gym "
        "membership.",
    ),
    (
        "Code of Conduct and Leaving Northwind",
        "Gifts from suppliers worth more than 2,000 rupees must be declared to the ethics team. Concerns about "
        "misconduct can be reported confidentially to ethics@northwind.example. The notice period for resignation "
        "is 60 days.",
    ),
]

# (question, page that answers it, word the correct answer must contain)
QUESTIONS = [
    ("When was Northwind founded?", 1, "2009"),
    ("How many people does Northwind employ?", 1, "1,200"),
    ("Who is the chief executive officer of Northwind?", 1, "Meera"),
    ("How many hours is the standard working week?", 2, "40"),
    ("What are the core hours?", 2, "10:00"),
    ("Between which times can employees start their working day?", 2, "8:00"),
    ("How many days of paid annual leave do employees get?", 3, "24"),
    ("How many unused annual leave days can be carried over?", 3, "10"),
    ("How many days of paid sick leave are there per year?", 3, "12"),
    ("How long is paid maternity leave?", 4, "26"),
    ("How many weeks of paternity leave do fathers get?", 4, "4"),
    ("How much paid adoption leave is given?", 4, "12"),
    ("How many days per week can employees work from home?", 5, "3"),
    ("How much is the home internet allowance?", 5, "1,500"),
    ("Where must remote workers be reachable during core hours?", 5, "Slack"),
    ("Within how many days must expense claims be submitted?", 6, "30"),
    ("What is the daily meal allowance on business trips?", 6, "800"),
    ("Which class are flights shorter than 6 hours booked in?", 6, "economy"),
    ("How often is the company laptop replaced?", 7, "3"),
    ("How long must passwords be?", 7, "14"),
    ("Who do I report a lost or stolen device to?", 7, "it-security"),
    ("What is the yearly learning budget?", 8, "40,000"),
    ("How many paid learning days per year are allowed?", 8, "5"),
    ("When are professional certification fees reimbursed?", 8, "passed"),
    ("How often do performance reviews take place?", 9, "twice"),
    ("What scale do managers use to rate performance?", 9, "5"),
    ("When are promotions and salary increases announced?", 9, "April"),
    ("How much health insurance cover is provided per year?", 10, "5 lakh"),
    ("What share of a gym membership does Northwind pay?", 10, "50"),
    ("What is the notice period for resignation?", 11, "60"),
]

UNANSWERABLE = [
    "What is Northwind's stock ticker symbol?",
    "Does the cafeteria serve vegan lunch on Fridays?",
]


def build_pdf(path: str) -> None:
    import pymupdf

    doc = pymupdf.open()
    for number, (title, body) in enumerate(PAGES, start=1):
        page = doc.new_page()
        page.insert_text((72, 80), title, fontsize=18)
        page.insert_textbox(pymupdf.Rect(72, 110, 523, 700), body, fontsize=11)
        page.insert_text((72, 800), f"Northwind Employee Handbook - page {number}", fontsize=8)
    doc.save(path)


if __name__ == "__main__":
    import sys

    build_pdf(sys.argv[1] if len(sys.argv) > 1 else "northwind-handbook.pdf")
